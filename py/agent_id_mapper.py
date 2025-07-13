#!/usr/bin/env python3
"""
Agent ID synchronization system for binary protocol.
Maps UUIDs to compact agent IDs for ultra-efficient communication.
"""

import json
import asyncio
import aiohttp
from typing import Dict, Set
import logging

class AgentIdMapper:
    """Maps UUIDs to compact agent IDs for binary protocol efficiency."""
    
    def __init__(self):
        self.uuid_to_id: Dict[str, int] = {}
        self.id_to_uuid: Dict[int, str] = {}
        self.known_agents: Set[str] = set()
        
    def add_agent(self, uuid: str, agent_id: int):
        """Register a new agent mapping."""
        self.uuid_to_id[uuid] = agent_id
        self.id_to_uuid[agent_id] = uuid
        self.known_agents.add(uuid)
        
    def get_agent_id(self, uuid: str) -> int:
        """Get agent ID for UUID, return 0 if unknown."""
        return self.uuid_to_id.get(uuid, 0)
        
    def get_uuid(self, agent_id: int) -> str:
        """Get UUID for agent ID, return empty string if unknown."""
        return self.id_to_uuid.get(agent_id, "")
        
    def remove_agent(self, uuid: str):
        """Remove agent mapping."""
        if uuid in self.uuid_to_id:
            agent_id = self.uuid_to_id[uuid]
            del self.uuid_to_id[uuid]
            del self.id_to_uuid[agent_id]
            self.known_agents.discard(uuid)
            
    def get_stats(self) -> dict:
        """Get mapping statistics."""
        return {
            "total_agents": len(self.known_agents),
            "active_mappings": len(self.uuid_to_id),
            "max_agent_id": max(self.id_to_uuid.keys()) if self.id_to_uuid else 0
        }
        
    def convert_actions_to_binary_format(self, actions: list) -> list:
        """Convert UUID-based actions to agent ID-based actions for binary protocol."""
        binary_actions = []
        
        for action in actions:
            uuid = action.get("id", "")
            agent_id = self.get_agent_id(uuid)
            
            if agent_id == 0:
                # Unknown agent - skip or log warning
                logging.warning(f"Unknown agent UUID: {uuid[:8]}...")
                continue
                
            binary_action = {
                'id': agent_id,
                'fire': action.get("type") == "fire",
                'move': action.get("type") == "joystick_vector"
            }
            
            if action.get("type") == "joystick_vector" and "vector" in action:
                binary_action['angle'] = action["vector"].get("angle", 0.0)
            else:
                binary_action['angle'] = 0.0
                
            binary_actions.append(binary_action)
            
        return binary_actions

# Global mapper instance
agent_mapper = AgentIdMapper()

async def sync_agent_ids_from_java():
    """Periodically sync agent IDs from Java server."""
    while True:
        try:
            # Request agent list from Java (we'll need to add this endpoint)
            async with aiohttp.ClientSession() as session:
                async with session.get("http://localhost:8052/agents") as response:
                    if response.status == 200:
                        agents_data = await response.json()
                        
                        # Update mappings
                        for agent_info in agents_data.get("agents", []):
                            uuid = agent_info.get("uuid")
                            agent_id = agent_info.get("agent_id")
                            if uuid and agent_id:
                                agent_mapper.add_agent(uuid, agent_id)
                                
                        logging.info(f"Synced {len(agents_data.get('agents', []))} agent mappings")
                        
        except Exception as e:
            logging.warning(f"Failed to sync agent IDs: {e}")
            
        # Wait 5 seconds before next sync
        await asyncio.sleep(5)

if __name__ == "__main__":
    # Test the mapper
    mapper = AgentIdMapper()
    
    # Simulate some agents
    test_agents = [
        ("550e8400-e29b-41d4-a716-446655440001", 1),
        ("550e8400-e29b-41d4-a716-446655440002", 2),
        ("550e8400-e29b-41d4-a716-446655440003", 3),
    ]
    
    for uuid, agent_id in test_agents:
        mapper.add_agent(uuid, agent_id)
        
    print("🎯 Agent ID Mapper Test:")
    print(f"   Stats: {mapper.get_stats()}")
    
    # Test conversion
    test_actions = [
        {"type": "joystick_vector", "id": "550e8400-e29b-41d4-a716-446655440001", "vector": {"angle": 45.0}},
        {"type": "fire", "id": "550e8400-e29b-41d4-a716-446655440002"},
        {"type": "joystick_vector", "id": "unknown-uuid", "vector": {"angle": 90.0}},  # Should be skipped
    ]
    
    binary_actions = mapper.convert_actions_to_binary_format(test_actions)
    print(f"   Converted {len(test_actions)} → {len(binary_actions)} actions")
    print(f"   Binary actions: {binary_actions}")
