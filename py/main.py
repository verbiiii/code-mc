#!/usr/bin/env python3
"""
Clean, high-performance main server for vectorized RL training.
Handles binary WebSocket protocol only - no dashboard, no JSON, pure speed.
"""

import asyncio
import logging
import json
from fastapi import FastAPI, WebSocket

from binary_transport import initialize_transport, process_binary_data, signal_round_end, get_stats, process_top_agent_data

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Global event loop reference (needed for proper WebSocket handling)
event_loop = None

# Initialize the vectorized transport layer
initialize_transport(device='cpu')

# FastAPI app - minimal setup
app = FastAPI(title="Minekov Vectorized RL Server")

# Track connected clients
connected_clients = set()

@app.websocket("/socket")
async def websocket_endpoint(websocket: WebSocket):
    """Ultra-fast binary WebSocket handler for RL observations and actions."""
    await websocket.accept()
    connected_clients.add(websocket)
    client_id = id(websocket)
    
    logger.info(f"🔗 Client {client_id} connected. Total clients: {len(connected_clients)}")
    
    try:
        while True:
            # Receive any message and check its type
            message = await websocket.receive()
            
            if "text" in message:
                # Handle JSON messages
                data = message["text"]
                try:
                    payload = json.loads(data)
                    message_type = payload.get("type")
                    
                    if message_type == "sync_operators":
                        continue
                    elif message_type == "round_start":
                        # Reduced logging noise
                        continue
                    elif message_type == "round_end":
                        logger.info("🔴 Round ended - applying learning updates")
                        signal_round_end()
                        continue
                    elif message_type == "tick":
                        logger.warning("⚠️ Received JSON tick - Java should use binary protocol")
                        continue
                    else:
                        logger.info(f"📝 Unknown message: {message_type}")
                        continue
                        
                except json.JSONDecodeError:
                    logger.warning(f"⚠️ Invalid JSON: {data}")
                    continue
                    
            elif "bytes" in message:
                # Handle binary messages
                binary_data = message["bytes"]
                
                # Process through vectorized pipeline and get actions
                response_data = process_binary_data(binary_data)
                
                # Send binary action response
                await websocket.send_bytes(response_data)
                
            else:
                logger.warning(f"⚠️ Unknown message type: {message}")
            
    except Exception as e:
        logger.warning(f"⚡ Client {client_id} disconnected: {e}")
    finally:
        connected_clients.discard(websocket)
        logger.info(f"❌ Client {client_id} removed. Total clients: {len(connected_clients)}")

@app.websocket("/top-agent")
async def top_agent_websocket(websocket: WebSocket):
    """Dedicated WebSocket endpoint for 1v1 combat with the top performing agent."""
    await websocket.accept()
    client_id = id(websocket)
    
    logger.info(f"🥊 Top agent client {client_id} connected for 1v1 combat")
    
    try:
        while True:
            # Receive binary observation data
            message = await websocket.receive()
            
            if "bytes" in message:
                binary_data = message["bytes"]
                
                # Process single agent observation through top agent model
                response_data = process_top_agent_data(binary_data)
                
                # Send binary action response
                await websocket.send_bytes(response_data)
                
            else:
                logger.warning(f"⚠️ Top agent endpoint only accepts binary data")
            
    except Exception as e:
        logger.warning(f"⚡ Top agent client {client_id} disconnected: {e}")
    finally:
        logger.info(f"❌ Top agent client {client_id} removed")

# All communication happens over WebSocket - no HTTP routes needed

async def main():
    """Run the server with proper event loop setup."""
    global event_loop
    import uvicorn
    
    logger.info("🚀 Starting Minekov Vectorized RL Server...")
    
    config = uvicorn.Config(
        app, 
        host="0.0.0.0", 
        port=8050,
        log_level="info",
        access_log=False,  # Disable access logs for performance
        loop="asyncio"
    )
    
    server = uvicorn.Server(config)
    await server.serve()

if __name__ == "__main__":
    try:
        event_loop = asyncio.new_event_loop()
        asyncio.set_event_loop(event_loop)
        event_loop.run_until_complete(main())
    except KeyboardInterrupt:
        logger.info("🛑 Server stopped by user")
    except Exception as e:
        logger.error(f"💥 Server error: {e}")
