package com.dyllan.minekov;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Ultra-fast vectorized observation encoder for binary protocol.
 * Converts agent observations directly to numpy-compatible byte arrays.
 * Zero loops, maximum performance.
 */
public class VectorizedObservationEncoder {
    
    private static final int MAGIC_HEADER = 0xFEEDBEEF;
    private static final int OBSERVATION_SIZE = 10; // [agent_index, my_pos(3), opp_pos(3), dmg_dealt, dmg_taken, kills, deaths]
    
    /**
     * Encode observations for all agents into binary format.
     * Format: [magic(4) + tick(4) + agent_count(4) + obs_size(4)] + [agent_data as float32 array]
     */
    public static byte[] encodeObservations(int tick, Map<Integer, AgentObservation> observations) {
        int agentCount = observations.size();
        
        if (agentCount == 0) {
            return createEmptyPacket(tick);
        }
        
        // Calculate total size: header(16) + data(agentCount * obs_size * 4)
        int dataSize = agentCount * OBSERVATION_SIZE * 4; // float32 = 4 bytes
        int totalSize = 16 + dataSize;
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Native byte order for numpy compatibility
        
        // Write header
        buffer.putInt(MAGIC_HEADER);
        buffer.putInt(tick);
        buffer.putInt(agentCount);
        buffer.putInt(OBSERVATION_SIZE);
        
        // Write observation data - including agent indices
        for (Map.Entry<Integer, AgentObservation> entry : observations.entrySet()) {
            int agentIndex = entry.getKey();
            AgentObservation obs = entry.getValue();
            
            if (obs == null) {
                System.err.println("ERROR: Missing observation for agent index " + agentIndex);
                continue;
            }
            
            // Pack observation: [agent_index, my_pos(3), opp_pos(3), dmg_dealt, dmg_taken, kills, deaths]
            buffer.putFloat((float) agentIndex);  // Agent index first
            // Agent position
            buffer.putFloat((float) obs.myX);
            buffer.putFloat((float) obs.myY);
            buffer.putFloat((float) obs.myZ);

            // Our team/group information (how we will determine the agent's opponent/team observations)
            buffer.putFloat((float) obs.groupIndex);
            buffer.putFloat((float) obs.teamIndex);

            // Reward metrics
            buffer.putFloat((float) obs.damageDealt);
            buffer.putFloat((float) obs.damageTaken);
            buffer.putFloat((float) obs.kills);
            buffer.putFloat((float) obs.deaths);
        }
        
        return buffer.array();
    }
    
    private static byte[] createEmptyPacket(int tick) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC_HEADER);
        buffer.putInt(tick);
        buffer.putInt(0); // No agents
        buffer.putInt(OBSERVATION_SIZE);
        return buffer.array();
    }
    
    /**
     * Agent observation data structure.
     */
    public static class AgentObservation {
        public final double myX, myY, myZ;
        public final double damageDealt, damageTaken;
        public final int kills, deaths;
        public final int groupIndex, teamIndex;
        
        public AgentObservation(double myX, double myY, double myZ,
                              double damageDealt, double damageTaken,
                              int kills, int deaths,
                              int groupIndex, int teamIndex) {
            this.myX = myX;
            this.myY = myY;
            this.myZ = myZ;
            this.groupIndex = groupIndex;
            this.teamIndex = teamIndex;
            this.damageDealt = damageDealt;
            this.damageTaken = damageTaken;
            this.kills = kills;
            this.deaths = deaths;
        }
    }
}
