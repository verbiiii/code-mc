#!/usr/bin/env python3
"""
Ultra-compact binary encoder for WebSocket action messages.
Reduces 64-agent messages from ~5KB to ~800 bytes (85% reduction).
"""

import struct
import asyncio
import websockets
import json

class UltraCompactEncoder:
    """Encodes agent actions into ultra-efficient binary format."""
    
    def encode_actions(self, actions):
        """
        Encode list of actions into binary WebSocket frame.
        
        Format:
        - Header: 4 bytes (magic + action_count)
        - Actions: 12 bytes each (agent_id + angle + flags + reserved)
        
        Args:
            actions: List of dicts with 'id', 'angle', 'fire', 'move' keys
            
        Returns:
            bytes: Binary data ready for WebSocket transmission
        """
        # Header: magic (0xACE5) + action count
        header = struct.pack('>HH', 0xACE5, len(actions))
        
        action_data = b''
        for action in actions:
            agent_id = action['id']  # Now just a small integer!
            angle = int(action.get('angle', 0) * 65535 / 360) & 0xFFFF
            flags = (
                (1 if action.get('fire') else 0) |
                (2 if action.get('move') else 0) |
                (4 if action.get('sprint') else 0) |
                (8 if action.get('crouch') else 0)
            )
            reserved = 0
            
            # Pack: agent_id(4) + angle(2) + flags(2) + reserved(4) = 12 bytes
            action_data += struct.pack('>IHHH', agent_id, angle, flags, reserved)
        
        return header + action_data

# Test the encoder
if __name__ == "__main__":
    encoder = UltraCompactEncoder()
    
    # Test with sample actions
    test_actions = [
        {'id': 1, 'angle': 45.0, 'fire': True, 'move': True},
        {'id': 2, 'angle': 180.0, 'move': True, 'sprint': True},
        {'id': 3, 'angle': 270.0, 'fire': True},
        {'id': 4, 'angle': 0.0, 'move': True},
    ]
    
    # Encode to binary
    binary_data = encoder.encode_actions(test_actions)
    
    print(f"🔥 Binary Protocol Test:")
    print(f"   Actions: {len(test_actions)}")
    print(f"   Binary size: {len(binary_data)} bytes")
    print(f"   JSON size (est): {len(json.dumps(test_actions))} bytes")
    print(f"   Compression: {(1 - len(binary_data) / len(json.dumps(test_actions))) * 100:.1f}%")
    print(f"   Hex dump: {binary_data.hex()}")
    
    # Verify header
    magic, count = struct.unpack('>HH', binary_data[:4])
    print(f"   Magic: 0x{magic:04X} ({'✅' if magic == 0xACE5 else '❌'})")
    print(f"   Count: {count} ({'✅' if count == len(test_actions) else '❌'})")
