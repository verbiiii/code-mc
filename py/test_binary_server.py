#!/usr/bin/env python3
"""
Test WebSocket server that handles both JSON and binary protocols.
Use this to test the new ultra-efficient binary action system.
"""

import asyncio
import websockets
import json
import struct
from binary_encoder import UltraCompactEncoder

class TestActionServer:
    def __init__(self):
        self.encoder = UltraCompactEncoder()
        self.agent_count = 0
        
    async def handle_client(self, websocket, path):
        print(f"🔗 Client connected: {websocket.remote_address}")
        
        try:
            async for message in websocket:
                if isinstance(message, str):
                    # JSON message
                    await self.handle_json_message(websocket, message)
                elif isinstance(message, bytes):
                    # Binary message  
                    await self.handle_binary_message(websocket, message)
        
        except websockets.exceptions.ConnectionClosed:
            print(f"🔌 Client disconnected: {websocket.remote_address}")
        except Exception as e:
            print(f"❌ Error handling client: {e}")
    
    async def handle_json_message(self, websocket, message):
        """Handle legacy JSON messages"""
        try:
            data = json.loads(message)
            print(f"📄 JSON: {data.get('type', 'unknown')} - {len(message)} bytes")
            
            if data.get('type') == 'hello':
                # Send test binary actions
                await self.send_test_actions(websocket)
                
        except json.JSONDecodeError as e:
            print(f"❌ Invalid JSON: {e}")
    
    async def handle_binary_message(self, websocket, data):
        """Handle binary action confirmations"""
        try:
            # Parse binary data
            if len(data) < 4:
                print("❌ Binary message too short")
                return
                
            magic, count = struct.unpack('>HH', data[:4])
            if magic != 0xACE5:
                print(f"❌ Invalid magic: 0x{magic:04X}")
                return
                
            print(f"🔥 Binary: {count} actions - {len(data)} bytes")
            
        except struct.error as e:
            print(f"❌ Binary parse error: {e}")
    
    async def send_test_actions(self, websocket):
        """Send test actions in binary format"""
        print("🚀 Sending test binary actions...")
        
        # Create test actions with incremental agent IDs
        actions = []
        for i in range(1, 65):  # 64 agents
            actions.append({
                'id': i,
                'angle': (i * 5.625) % 360,  # Different angles
                'fire': i % 3 == 0,          # Every 3rd agent fires
                'move': True,                # All agents move
                'sprint': i % 5 == 0         # Every 5th agent sprints
            })
        
        # Encode to binary
        binary_data = self.encoder.encode_actions(actions)
        
        # Send as binary WebSocket frame
        await websocket.send(binary_data)
        
        print(f"📦 Sent {len(actions)} actions ({len(binary_data)} bytes)")
        
        # Also show JSON comparison
        json_size = len(json.dumps(actions))
        compression = (1 - len(binary_data) / json_size) * 100
        print(f"📊 Compression: {compression:.1f}% ({json_size} → {len(binary_data)} bytes)")

async def main():
    server = TestActionServer()
    
    print("🎯 Starting test WebSocket server on ws://localhost:8051")
    print("💡 This server tests both JSON and binary protocols")
    print("🔥 Connect your Java client to see ultra-efficient binary in action!")
    
    start_server = websockets.serve(server.handle_client, "localhost", 8051)
    
    await start_server

if __name__ == "__main__":
    asyncio.run(main())
