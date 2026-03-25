#!/usr/bin/env python3
"""
Clean, high-performance main server for vectorized RL training.
Handles binary WebSocket protocol only - no dashboard, no JSON, pure speed.
"""

import asyncio
import logging
import json
from fastapi import FastAPI, WebSocket
from starlette.websockets import WebSocketDisconnect

from train_vectorized import VectorizedTrainer, USE_WANDB
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

    if len(connected_clients) > 1:
        connected_clients.discard(websocket)
        await websocket.close(code=1008, reason="Only one client allowed.")
        return

    # Reset state so a freshly started Python process can re-initialize from session_start
    TRAINER = None
    TRANSPORT = None

    try:
        while True:
            # Receive any message and check its type
            message = await websocket.receive()
            if "text" in message:
                # Handle JSON messages
                data = message["text"]
                try:
                    payload = json.loads(data)
                except json.JSONDecodeError:
                    logger.warning(f"⚠️ Invalid JSON: {data}")
                    continue

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

                    print(f"Initializing trainer with {num_agents} agents.")

                    TRAINER = VectorizedTrainer(
                        num_agents=num_agents,
                    )
                    TRANSPORT = BinaryTransport(trainer=TRAINER)
                    if getattr(TRAINER, "wandb_url", None):
                        try:
                            await websocket.send_text(
                                json.dumps({"type": "wandb_url", "url": TRAINER.wandb_url})
                            )
                        except Exception as e:
                            logger.warning(f"Could not send W&B URL to client: {e}")
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

            elif "bytes" in message:
                if TRANSPORT is None or TRAINER is None:
                    logger.warning("⚠️ Binary data received before session_start — dropping until initialized.")
                    continue

                # Handle binary messages
                binary_data = message["bytes"]

                # Process through vectorized pipeline and get actions
                response_data = TRANSPORT.process_observations(binary_data)

                # Send binary action response (client may disconnect during shutdown)
                try:
                    await websocket.send_bytes(response_data)
                except (WebSocketDisconnect, asyncio.CancelledError):
                    break
            else:
                logger.warning(f"⚠️ Unknown message type: {message}")
    except (WebSocketDisconnect, asyncio.CancelledError):
        pass
    finally:
        connected_clients.discard(websocket)
        if USE_WANDB:
            try:
                import wandb

                if wandb.run is not None:
                    wandb.finish()
            except Exception:
                pass
        logger.info(f"🔌 Client {client_id} disconnected. Total clients: {len(connected_clients)}")

async def main():
    """Run the server with proper event loop setup."""
    global event_loop
    import uvicorn
    
    logger.info("🚀 Starting Minekov Vectorized RL Server...")
    
    config = uvicorn.Config(
        app, 
        host="0.0.0.0", 
        port=8050,
        log_level="warning",
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
        print("User stopping training session")
    except Exception as e:
        logger.error(f"💥 Server error: {e}")
