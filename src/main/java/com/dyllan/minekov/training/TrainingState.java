package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dyllan.minekov.ModEntities;
import com.dyllan.minekov.PythonBridge;
import com.dyllan.minekov.VectorizedObservationEncoder;
import com.dyllan.minekov.entities.AIOperator;
import com.dyllan.minekov.entities.DumbOperator;
import com.dyllan.minekov.entities.RLOperator;

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

    public TrainingState(Player provisioningPlayer, MinecraftServer server, int rounds) {
        this.numRounds = rounds;
        this.provisioningPlayer = provisioningPlayer;
        this.server = server;

        // TODO: pre-determine the number of operators better than this
        this.operatorsArray = new AIOperator[NUM_GROUPS * 2]; // 2 operators per group

        // No JSON messages - only binary observations for performance
        setupRound(); // begin first round
    }

    public AIOperator getOperator(int index) {
        if (index < 0 || index >= operatorsArray.length) {
            throw new IndexOutOfBoundsException("Invalid operator index: " + index);
        }
        return operatorsArray[index];
    }

    public RLOperator getRLOperator(int index) {
        AIOperator operator = getOperator(index);
        if (operator instanceof RLOperator) {
            return (RLOperator) operator;
        } else {
            throw new IllegalArgumentException("Operator at index " + index + " is not an RLOperator");
        }
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

    public void tick() {
        if (!roundActive) return;

        globalTick++;

        boolean roundDone = isRoundComplete();

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
            int agentId = rlOp.getId(); // Entity ID as unique identifier
            observations.put(agentId, obs);
            rlOp.clearTickDamageStats();
        }
        
        // Encode and send binary observations
        byte[] binaryData = VectorizedObservationEncoder.encodeObservations(globalTick, observations);
        PythonBridge.sendBinaryToPython(binaryData);

        if (roundDone) {
            cleanupRound();
            currentRound++;

            if (currentRound >= numRounds) {
                endSession();
            } else {
                setupRound();
            }
        }
    }

    public boolean isComplete() {
        return !roundActive && currentRound >= numRounds;
    }

    private boolean isRoundComplete() {
        return groups.stream().allMatch(TrainingGroup::isComplete);
    }

    private void setupRound() {
        // sanity check: guarentee all values within the operators array are null (raise an error if not)
        for (int i = 0; i < operatorsArray.length; i++) {
            if (operatorsArray[i] != null) {
                throw new IllegalStateException("Operator array not cleared properly before new round setup!");
            }
        }

        System.out.println("🚀 Setting up round " + (currentRound + 1));
        
        groups.clear();
        roundActive = true;

        ServerLevel world = server.overworld();
        double team1X = 19.5, team1Z = 17.5;
        double team2X = 19.5, team2Z = 9.5;
        double baseY = 2.0;

        // Let's create a temporary array list to hold the operators, after which we will put them in the array
        List<AIOperator> currentlyInitializedOperators = new ArrayList<>();

        for (int i = 0; i < NUM_GROUPS; i++) {
            double y = baseY;

            TrainingGroup group = new TrainingGroup(200); // 600 ticks is 30 seconds

            RLOperator rl1 = ModEntities.RL_OPERATOR.get().create(world);
            currentlyInitializedOperators.add(rl1);
            rl1.moveTo(team1X, y, team1Z, 180.0f, 0.0f);
            world.addFreshEntity(rl1);
            Team team1 = new Team();
            team1.addOperator(rl1);

            AIOperator opponent;
            if (selfPlay) {
                RLOperator rl2 = ModEntities.RL_OPERATOR.get().create(world);
                rl2.moveTo(team2X, y, team2Z, 0.0f, 0.0f);
                world.addFreshEntity(rl2);
                opponent = rl2;
                currentlyInitializedOperators.add(rl2);
            } else {
                DumbOperator dumb = ModEntities.DUMB_OPERATOR.get().create(world);
                dumb.moveTo(team2X, y, team2Z, 0.0f, 0.0f);
                world.addFreshEntity(dumb);
                opponent = dumb;
                currentlyInitializedOperators.add(dumb);
            }
            Team team2 = new Team();
            team2.addOperator(opponent);

            group.addTeam(team1);
            group.addTeam(team2);
            groups.add(group);
        }

        // now, let's raise an exception if the number of initialized operators does not match the length of our array
        if (currentlyInitializedOperators.size() != operatorsArray.length) {
            throw new IllegalStateException("Number of initialized operators does not match expected size!");
        }

        // otherwise, let's add the references to the operators array, implicitly assigning them indices
        for (int i = 0; i < currentlyInitializedOperators.size(); i++) {
            operatorsArray[i] = currentlyInitializedOperators.get(i);
        }

        // No JSON messages - only binary protocol
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
