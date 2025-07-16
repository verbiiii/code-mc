#!/usr/bin/env python3
"""
Clean, high-performance main server for vectorized RL training.
Handles binary WebSocket protocol only - no dashboard, no JSON, pure speed.
"""

import asyncio
import logging
import json
from fastapi import FastAPI, WebSocket

from train_vectorized import VectorizedTrainer
from binary_transport import BinaryTransport

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Global event loop reference (needed for proper WebSocket handling)
event_loop = None

# Initialize the vectorized transport layer
TRAINER = None
TRANSPORT = None

# FastAPI app - minimal setup
app = FastAPI(title="Minekov Vectorized RL Server")

# Track connected clients
connected_clients = set()

@app.websocket("/socket")
async def websocket_endpoint(websocket: WebSocket):
    """Ultra-fast binary WebSocket handler for RL observations and actions."""
    global TRAINER, TRANSPORT

    await websocket.accept()
    connected_clients.add(websocket)
    client_id = id(websocket)
    logger.info(f"🔗 Client {client_id} connected. Total clients: {len(connected_clients)}")
    
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
                elif message_type == "session_start":
                    logger.info("🔵 Session started - initializing training")

                    num_agents = payload["num_agents"]
                    # spawn_radius = payload["spawn_radius"]
                    # spawn_center_x = payload["center_x"]
                    # spawn_center_y = payload["center_y"]
                    # spawn_center_z = payload["center_z"]

                    if TRAINER is not None or TRANSPORT is not None:
                        raise ValueError("TODO")
                    
                    print(f"Initializing trainer with {num_agents} agents.")

                    TRAINER = VectorizedTrainer(
                        num_agents=num_agents,
                    )
                    TRANSPORT = BinaryTransport(trainer=TRAINER)

                    # trainer.on_session_start(payload)
                    continue
                elif message_type == "round_start":
                    # Reduced logging noise
                    continue
                elif message_type == "round_end":
                    update_model = payload.get("update_model_parameters", True)  # Default to True for backwards compatibility
                    if update_model:
                        logger.info("🔴 Round ended - applying learning updates")
                        TRAINER.on_round_end()
                    else:
                        logger.info("🔄 Round ended - skipping learning updates (play mode)")
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
            if TRANSPORT is None or TRAINER is None:
                logger.error("❌ Transport or trainer not initialized. Cannot process binary data.")
                await websocket.close(code=1001, reason="Transport not initialized")
                return

            # Handle binary messages
            binary_data = message["bytes"]
            
            # Process through vectorized pipeline and get actions
            response_data = TRANSPORT.process_observations(binary_data)
            
            # Send binary action response
            await websocket.send_bytes(response_data)
            
        else:
            logger.warning(f"⚠️ Unknown message type: {message}")

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
