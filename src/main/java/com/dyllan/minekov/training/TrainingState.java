package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.dyllan.minekov.PythonBridge;
import com.dyllan.minekov.PythonRLController;
import com.dyllan.minekov.VectorizedActionDecoder;
import com.dyllan.minekov.VectorizedObservationEncoder;
import com.dyllan.minekov.entities.AIOperator;
import com.dyllan.minekov.entities.DumbOperator;
import com.dyllan.minekov.entities.OperatorSpawningHandler;
import com.dyllan.minekov.entities.RLOperator;
import com.eliotlash.mclib.math.Operator;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

public class TrainingState {
    private static final int NUM_GROUPS = 32; // ← change this to 1, 100, etc. for # of 1v1s
    private final boolean selfPlay = true; // ← set to false to use DumbOperator

    private List<TrainingGroup> groups = new ArrayList<>();
    private Player provisioningPlayer;
    private final MinecraftServer server;

    private final AIOperator[] operatorsArray;

    private final int numRounds;
    private int currentRound = 0;
    private int globalTick = 0;
    private boolean roundActive = false;

    private final OperatorSpawningHandler operatorSpawningHandler;

    public TrainingState(Player provisioningPlayer, MinecraftServer server, int rounds, OperatorSpawningHandler operatorSpawningHandler) {
        this.numRounds = rounds;
        this.provisioningPlayer = provisioningPlayer;
        this.server = server;
        this.operatorSpawningHandler = operatorSpawningHandler;

        // TODO: pre-determine the number of operators better than this
        this.operatorsArray = new AIOperator[NUM_GROUPS * 2]; // 2 operators per group

        // No JSON messages - only binary observations for performance
        setupRound(); // begin first round
    }

    public void tick() {
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
            // only look at the group that contains the operator (SHOULD ONLY BE CONTAINED BY ONE TODO make that always true)
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

            // Get the opponent from the same group (TODO: multiple opponents/team mates)
            AIOperator opponent = getOpponentsForOperator(rlOp).get(0);
            
            // Create vectorized observation with actual agent ID
            float damageDealt = rlOp.getDamageDealtLastTick();
            float damageTaken = rlOp.getDamageTakenLastTick();
            int kills = rlOp.getKillsLastTick();
            int deaths = rlOp.getDeathsLastTick();
            
            VectorizedObservationEncoder.AgentObservation obs = new VectorizedObservationEncoder.AgentObservation(
                rlOp.getX(), rlOp.getY(), rlOp.getZ(),       // Agent position
                opponent.getX(), opponent.getY(), opponent.getZ(), // Opponent position
                damageDealt,                                 // Damage dealt
                damageTaken,                                 // Damage taken
                kills,                                       // Kills
                deaths                                       // Deaths
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

    public boolean isComplete() {
        return !roundActive && currentRound >= numRounds;
    }

    private boolean isRoundComplete() {
        return groups.stream().allMatch(TrainingGroup::isRoundComplete);
    }

    private void setupRound() {
        // sanity check: guarantee all values within the operators array are null (raise an error if not)
        for (int i = 0; i < operatorsArray.length; i++) {
            if (operatorsArray[i] != null) {
                throw new IllegalStateException("Operator array not cleared properly before new round setup!");
            }
        }

        System.out.println("🚀 Setting up round " + (currentRound + 1));

        groups.clear();
        roundActive = true;

        ServerLevel world = server.overworld();
        List<AIOperator> currentlyInitializedOperators = new ArrayList<>();

        for (int i = 0; i < NUM_GROUPS; i++) {
            TrainingGroup group = new TrainingGroup(200); // 600 ticks = 30 seconds

            // Spawn RL operator
            RLOperator rl1 = operatorSpawningHandler.spawnRLOperator();
            currentlyInitializedOperators.add(rl1);
            Team team1 = new Team();
            team1.addOperator(rl1);

            // Spawn opponent
            AIOperator opponent;
            if (selfPlay) {
                RLOperator rl2 = operatorSpawningHandler.spawnRLOperator();
                currentlyInitializedOperators.add(rl2);
                opponent = rl2;
            } else {
                DumbOperator dumb = operatorSpawningHandler.spawnDumbOperator();
                currentlyInitializedOperators.add(dumb);
                opponent = dumb;
            }
            Team team2 = new Team();
            team2.addOperator(opponent);

            // Add both teams to group
            group.addTeam(team1);
            group.addTeam(team2);
            groups.add(group);
        }

        // Validate total operator count
        if (currentlyInitializedOperators.size() != operatorsArray.length) {
            throw new IllegalStateException("Number of initialized operators does not match expected size!");
        }

        // Shuffle and store in array
        Collections.shuffle(currentlyInitializedOperators, new Random());
        for (int i = 0; i < currentlyInitializedOperators.size(); i++) {
            operatorsArray[i] = currentlyInitializedOperators.get(i);
        }

        // Announce round start
        broadcastToPlayers("§eRound " + (currentRound + 1) + " started!");
    }

    private void cleanupRound() {
        System.out.println("🧹 Cleaning up round " + (currentRound + 1) + " with " + groups.size() + " groups");
        
        roundActive = false;
        int totalEntitiesKilled = 0;
        
        // loop through our array and kill them all, making sure to null their positions
        for (int i = 0; i < operatorsArray.length; i++) {
            AIOperator operator = operatorsArray[i];
            if (operator != null) {
                operator.kill(); // Remove from world without triggering death mechanics
                totalEntitiesKilled++;
                operatorsArray[i] = null;
            }
        }

        groups.clear(); // Clear groups after cleanup
        System.out.println("✅ Round cleanup complete - killed " + totalEntitiesKilled + " entities");

        // Send round end signal to Python with training update flag
        com.dyllan.minekov.Minekov.sendRoundEnd(true); // Training mode - update parameters

        // No JSON messages - only binary protocol
        broadcastToPlayers("§cRound " + (currentRound + 1) + " complete.");
    }

    private void endSession() {
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
        if (roundActive) {
            cleanupRound();
            roundActive = false;
        }
        currentRound = 0;
        groups.clear();
        // No JSON messages - only binary protocol
        broadcastToPlayers("§cTraining session forcefully stopped.");
    }

    public List<TrainingGroup> getGroups() {
        return groups;
    }
}
