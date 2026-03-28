package com.verbii.minekov.training;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.verbii.minekov.Minekov;
import com.verbii.minekov.PythonBridge;
import com.verbii.minekov.PythonRLController;
import com.verbii.minekov.VectorizedActionDecoder;
import com.verbii.minekov.VectorizedObservationEncoder;
import com.verbii.minekov.entities.AIOperator;
import com.verbii.minekov.entities.DumbOperator;
import com.verbii.minekov.entities.OperatorSpawningHandler;
import com.verbii.minekov.entities.RLOperator;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

public class TrainingState {
    /**
     * Total RL agent slots ({@link #operatorsArray} length). For FFA: {@code agentsPerGroup * numGroups};
     * for 1v1: total agents in the session (even count).
     */
    private final int totalOperatorSlots;
    /** FFA only: RL agents per arena; for 1v1 unused (use {@link #totalOperatorSlots}). */
    private final int ffaAgentsPerGroup;
    /** FFA only: parallel arenas; 1v1 always 1. */
    private final int ffaNumGroups;
    private final boolean selfPlay = true; // ← set to false to use DumbOperator
    public final boolean allowRespawns = true;

    private List<TrainingGroup> groups = new ArrayList<>();
    private @Nullable Player provisioningPlayer;
    private final MinecraftServer server;

    private final AIOperator[] operatorsArray;

    private final int numRounds;
    private final int maxSecondsPerRound;
    private int currentRound = 0;
    private int globalTick = 0;
    private boolean roundActive = false;
    private boolean stopped = false;
    private boolean cleaningUp = false;

    private final TrainingGameMode mode;
    private final OperatorSpawningHandler operatorSpawningHandler;

    /**
     * @param numOperatorsForMode for {@link TrainingGameMode#FREE_FOR_ALL}: agents per group; for {@link TrainingGameMode#ONE_VS_ONE}: total agent count (even).
     * @param numGroups           parallel FFA arenas (default 1); ignored for 1v1.
     */
    public TrainingState(@Nullable Player provisioningPlayer, MinecraftServer server, int rounds, OperatorSpawningHandler operatorSpawningHandler, TrainingGameMode mode, int numOperatorsForMode, int numGroups, int maxSecondsPerRound) {
        this.numRounds = rounds;
        this.maxSecondsPerRound = maxSecondsPerRound;
        this.provisioningPlayer = provisioningPlayer;
        this.server = server;
        this.operatorSpawningHandler = operatorSpawningHandler;
        this.mode = mode;

        if (mode == TrainingGameMode.FREE_FOR_ALL) {
            this.ffaNumGroups = Math.max(1, numGroups);
            this.ffaAgentsPerGroup = numOperatorsForMode;
            this.totalOperatorSlots = this.ffaAgentsPerGroup * this.ffaNumGroups;
        } else {
            this.ffaNumGroups = 1;
            this.ffaAgentsPerGroup = numOperatorsForMode;
            this.totalOperatorSlots = numOperatorsForMode;
        }

        this.operatorsArray = new AIOperator[this.totalOperatorSlots];
        Minekov.sendTrainSessionStart(this.totalOperatorSlots, operatorSpawningHandler.getRadius(), operatorSpawningHandler.getCenter());

        // No JSON messages - only binary observations for performance
        setupRound(); // begin first round
    }

    public void tick() {
        if (stopped) {
            throw new IllegalStateException("Training session has been stopped, cannot tick.");
        }

        if (!roundActive) return;

        performOperatorActions();

        globalTick++;

        this.groups.forEach(TrainingGroup::tick);
        boolean roundDone = isRoundComplete();
        if (roundDone) {
            cleanupRound();
            currentRound++;
            if (currentRound >= numRounds) {
                endSession();
            } else {
                setupRound();
            }

            // no need to send observations and stuff, just skip the rest of the tick logic
            return;
        }

        givePythonOurObservations();

        Minekov.updateRLOperatorNametags(this);
    }

    public void performOperatorActions() {
        // The WebSocketClient will put all actions it receives from python into the
        // static ACTION_QUEUE in the PythonRLController class.
        // This is the method that will ingest those actions, applying them to the
        // current tick.
        var actionQueue = PythonRLController.ACTION_QUEUE;

        int size = actionQueue.size();
        if (size == 0) {
            // print another warning if the queue is empty
            System.out.println("⚠️ Warning: Action queue is empty, no actions received from Python. This may indicate a problem with the WebSocket connection or Python script.");
        } else if (size > 1) {
            System.out.println("⚠️ Warning: Action queue size is " + size + ", latency for action predictions appears to be slower than the main server tick loop.");            
        }

        // Poll and process each queued action map (thread-safe removal)
        Map<Integer, VectorizedActionDecoder.AgentAction> actions;
        while ((actions = actionQueue.poll()) != null) {
            for (Map.Entry<Integer, VectorizedActionDecoder.AgentAction> entry : actions.entrySet()) {
                int sequentialIndex = entry.getKey();
                VectorizedActionDecoder.AgentAction action = entry.getValue();
                RLOperator operator = getRLOperator(sequentialIndex);

                if (operator == null) {
                    throw new IllegalStateException("No RLOperator found for index: " + sequentialIndex);
                }

                action.performAction(operator);
            }
        }
    }

    public int getIndexForRLOperator(RLOperator operator) {
        // custom for loop because the index skips over non-RLOperator entities

        // use getRLOperators()
        int i = 0;
        for (RLOperator op : getRLOperators()) {
            if (op == operator) {
                return i; // return the index of the RLOperator
            }
            i++;
        }

        throw new IllegalStateException("RLOperator not found in array: " + operator.getId());
    }

    public AIOperator getOperator(int index) {
        if (index < 0 || index >= operatorsArray.length) {
            throw new IndexOutOfBoundsException("Invalid operator index: " + index);
        }
        return operatorsArray[index];
    }

    public ArrayList<RLOperator> getRLOperators() {
        ArrayList<RLOperator> rlOperators = new ArrayList<>();
        for (AIOperator op : operatorsArray) {
            if (op instanceof RLOperator) {
                rlOperators.add((RLOperator) op);
            }
        }
        return rlOperators;
    }

    public RLOperator getRLOperator(int index) {
        // filter array first (kinda slow, but hmm)
        ArrayList<RLOperator> rlOperators = getRLOperators();

        // then check if the index is valid
        if (index < 0 || index >= rlOperators.size()) {
            throw new IndexOutOfBoundsException("Invalid RLOperator index: " + index);
        }

        return rlOperators.get(index);
    }

    public ArrayList<AIOperator> getOpponentsForOperator(AIOperator operator) {
        ArrayList<AIOperator> opponents = new ArrayList<>();
        for (TrainingGroup group : groups) {
            if (!group.contains(operator)) continue;

            for (Team team : group.getTeams()) {
                // ignore our own team
                if (team.getOperators().contains(operator)) continue;

                for (AIOperator op : team.getOperators()) {
                    if (op != operator && !opponents.contains(op)) {
                        opponents.add(op);
                    }
                }
            }
        }

        // raise illegal state exception if > 1 opponent found (temporarily)
        if (opponents.size() > 1) {
            throw new IllegalStateException("Multiple opponents found for operator " + operator.getId() + ": " + opponents.size());
        }

        return opponents;
    }

    public void givePythonOurObservations() {
        // 🚀 BINARY PROTOCOL - Ultra-fast vectorized observations
        Map<Integer, VectorizedObservationEncoder.AgentObservation> observations = new HashMap<>();
        
        for (int i = 0; i < operatorsArray.length; i++) {
            // if it's an RLOperator we can use it, otherwise let's skip
            if (!(operatorsArray[i] instanceof RLOperator)) continue;

            RLOperator rlOp = (RLOperator) operatorsArray[i];

            // Create vectorized observation with actual agent ID
            float damageDealt = rlOp.getDamageDealtLastTick();
            float damageTaken = rlOp.getDamageTakenLastTick();
            int kills = rlOp.getKillsLastTick();
            int deaths = rlOp.getDeathsLastTick();

            int groupIndex = getGroupIndexForRLOperator(rlOp);
            int teamIndex = getTeamIndexForRLOperator(rlOp);
            
            VectorizedObservationEncoder.AgentObservation obs = new VectorizedObservationEncoder.AgentObservation(
                rlOp.getX(), rlOp.getY(), rlOp.getZ(),       // Agent position
                damageDealt,                                 // Damage dealt
                damageTaken,                                 // Damage taken
                kills,                                       // Kills
                deaths,                                      // Deaths
                rlOp.getBulletsLastTick(),
                groupIndex,
                teamIndex,
                rlOp.getHealth()
            );
            
            // Use actual agent ID instead of sequential index
            // int agentId = rlOp.getId(); // Entity ID as unique identifier (THIS LINE IS A MAJOR BUG! HAD TO FIX BELOW)
            int opIndex = getIndexForRLOperator(rlOp);
            observations.put(opIndex, obs);
            rlOp.clearTickDamageStats();
        }
        
        // Encode and send binary observations
        byte[] binaryData = VectorizedObservationEncoder.encodeObservations(globalTick, observations);
        PythonBridge.sendBinaryToPython(binaryData);
    }

    public int getGroupIndexForRLOperator(RLOperator operator) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).contains(operator)) {
                return i; // Return the index of the group containing this operator
            }
        }
        throw new IllegalStateException("RLOperator not found in any group: " + operator.getId());
    }

    public int getTeamIndexForRLOperator(RLOperator operator) {
        for (TrainingGroup group : groups) {
            for (int i = 0; i < group.getTeams().size(); i++) {
                Team team = group.getTeams().get(i);
                if (team.getOperators().contains(operator)) {
                    return i; // Return the index of the team containing this operator
                }
            }
        }
        throw new IllegalStateException("RLOperator not found in any team: " + operator.getId());
    }

    public boolean isComplete() {
        return !roundActive && currentRound >= numRounds;
    }

    private boolean isRoundComplete() {
        return groups.stream().allMatch(TrainingGroup::isRoundComplete);
    }

    private void setupRound() {
        System.out.println("🚀 Setting up round " + (currentRound + 1));

        groups.clear();
        roundActive = true;

        boolean firstRoundSpawn = operatorsArray[0] == null;
        if (firstRoundSpawn) {
            List<AIOperator> initialized = spawnNewOperators();
            if (initialized.size() != operatorsArray.length) {
                throw new IllegalStateException("Number of initialized operators does not match expected size!");
            }
            Collections.shuffle(initialized, new Random());
            for (int i = 0; i < initialized.size(); i++) {
                operatorsArray[i] = initialized.get(i);
            }
        } else {
            List<AIOperator> ordered = new ArrayList<>(Arrays.asList(operatorsArray));
            Collections.shuffle(ordered, new Random());
            for (int i = 0; i < ordered.size(); i++) {
                operatorsArray[i] = ordered.get(i);
            }
        }

        // First round: spawns already placed operators; later rounds: teleport to fresh spawns.
        buildGroupsFromOperatorsArrayAndTeleport(!firstRoundSpawn);

        broadcastToPlayers("§eRound " + (currentRound + 1) + " started!");
    }

    /** First session only: create entities in the world. */
    private List<AIOperator> spawnNewOperators() {
        List<AIOperator> allOperators = new ArrayList<>();

        if (mode == TrainingGameMode.ONE_VS_ONE) {
            for (int i = 0; i < totalOperatorSlots / 2; i++) {
                RLOperator rl1 = operatorSpawningHandler.spawnRLOperator(0);
                allOperators.add(rl1);
                if (selfPlay) {
                    RLOperator rl2 = operatorSpawningHandler.spawnRLOperator(0);
                    allOperators.add(rl2);
                } else {
                    DumbOperator dumb = operatorSpawningHandler.spawnDumbOperator();
                    allOperators.add(dumb);
                }
            }
        } else if (mode == TrainingGameMode.FREE_FOR_ALL) {
            for (int g = 0; g < ffaNumGroups; g++) {
                for (int i = 0; i < ffaAgentsPerGroup; i++) {
                    RLOperator rl = operatorSpawningHandler.spawnRLOperator(g);
                    allOperators.add(rl);
                }
            }
        } else {
            throw new IllegalStateException("Unsupported game mode: " + mode);
        }

        return allOperators;
    }

    /** Rebuild teams/groups from {@link #operatorsArray}; optionally teleport (between rounds). */
    private void buildGroupsFromOperatorsArrayAndTeleport(boolean teleport) {
        int maxTicks = maxSecondsPerRound * 20;

        if (mode == TrainingGameMode.ONE_VS_ONE) {
            for (int i = 0; i < totalOperatorSlots / 2; i++) {
                TrainingGroup group = new TrainingGroup(maxTicks, allowRespawns);
                AIOperator op0 = operatorsArray[2 * i];
                AIOperator op1 = operatorsArray[2 * i + 1];
                Team team1 = new Team();
                team1.addOperator(op0);
                Team team2 = new Team();
                team2.addOperator(op1);
                group.addTeam(team1);
                group.addTeam(team2);
                groups.add(group);
                if (teleport) {
                    teleportOperatorForRound(op0, 0);
                    teleportOperatorForRound(op1, 0);
                }
            }
        } else if (mode == TrainingGameMode.FREE_FOR_ALL) {
            for (int g = 0; g < ffaNumGroups; g++) {
                TrainingGroup group = new TrainingGroup(maxTicks, allowRespawns);
                for (int i = 0; i < ffaAgentsPerGroup; i++) {
                    int slot = g * ffaAgentsPerGroup + i;
                    RLOperator rl = (RLOperator) operatorsArray[slot];
                    Team team = new Team();
                    team.addOperator(rl);
                    group.addTeam(team);
                    if (teleport) {
                        operatorSpawningHandler.teleportRLOperatorForRound(rl, g);
                    }
                }
                groups.add(group);
            }
            if (!teleport) {
                for (int g = 0; g < ffaNumGroups; g++) {
                    for (int i = 0; i < ffaAgentsPerGroup; i++) {
                        int slot = g * ffaAgentsPerGroup + i;
                        ((RLOperator) operatorsArray[slot]).setTrainingGroupId(g);
                    }
                }
            }
        } else {
            throw new IllegalStateException("Unsupported game mode: " + mode);
        }
    }

    private void teleportOperatorForRound(AIOperator op, int trainingGroupId) {
        if (op instanceof RLOperator rl) {
            operatorSpawningHandler.teleportRLOperatorForRound(rl, trainingGroupId);
        } else if (op instanceof DumbOperator dumb) {
            operatorSpawningHandler.teleportDumbOperatorForRound(dumb);
        } else {
            throw new IllegalStateException("Unexpected operator type: " + op.getClass());
        }
    }

    private void cleanupRound() {
        this.cleaningUp = true;

        System.out.println("🧹 Cleaning up round " + (currentRound + 1) + " with " + groups.size() + " groups");

        roundActive = false;
        groups.clear();

        System.out.println("✅ Round cleanup complete — preserving " + operatorsArray.length + " operator entities (teleport next round)");

        // Send round end signal to Python with training update flag
        com.verbii.minekov.Minekov.sendRoundEnd(true); // Training mode - update parameters

        // No JSON messages - only binary protocol
        broadcastToPlayers("§cRound " + (currentRound + 1) + " complete.");
        this.cleaningUp = false;
    }

    /** Remove all session operators from the world (session stop or natural end). */
    private void destroySessionOperators() {
        for (int i = 0; i < operatorsArray.length; i++) {
            AIOperator operator = operatorsArray[i];
            if (operator != null) {
                operator.kill();
                operatorsArray[i] = null;
            }
        }
        groups.clear();
    }

    private void endSession() {
        destroySessionOperators();
        // No JSON messages - only binary protocol
        broadcastToPlayers("§aTraining session complete!");
    }

    private void broadcastToPlayers(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    public Player getProvisioningPlayer() {
        return provisioningPlayer;
    }

    public void stop() {
        this.stopped = true;
        Minekov.clearEliteAgentIndices();
        if (roundActive) {
            cleanupRound();
            roundActive = false;
        }
        destroySessionOperators();
        currentRound = 0;
        // No JSON messages - only binary protocol
        broadcastToPlayers("§cTraining session forcefully stopped.");
    }

    public List<TrainingGroup> getGroups() {
        return groups;
    }

    public void resendSessionStart() {
        Minekov.sendTrainSessionStart(operatorsArray.length, operatorSpawningHandler.getRadius(), operatorSpawningHandler.getCenter());
    }

    public void onOperatorDeath(RLOperator operator) {
        operator.addDeath();

        // now, let's create a new operator in-place of them if respawns are enabled
        if (allowRespawns && !stopped) {
            if (!this.cleaningUp) {
                operatorSpawningHandler.respawnRLOperator(operator);
            }
        } else {
            // only clear the index if respawns are not allowed
            int index = getIndexForRLOperator(operator);
            operatorsArray[index] = null;
        }
    }

    public void onOperatorKill(RLOperator operator) {
        operator.addKill();
    }
}
