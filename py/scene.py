from dash import Dash, dcc, html, Input, Output, State, ALL
import dash
import numpy as np
import plotly.graph_objects as go
from fastapi import FastAPI, Request, WebSocket
from starlette.middleware.wsgi import WSGIMiddleware
from threading import Lock
import json
import asyncio

from fastapi.responses import JSONResponse
from starlette.requests import Request as StarletteRequest
from dash import ctx

from train import TrainState1v1


# ---------------- State ----------------
event_loop = None
operator_state = {}
connected_websockets = set()
volume_lock = Lock()
latest_update_flag = 0
volume = None

train_state = TrainState1v1()

# ---------------- Dash (WSGI App) ----------------
dash_app = Dash(__name__, routes_pathname_prefix="/")
server = WSGIMiddleware(dash_app.server)

# Dark theme
dark_theme = {
    "background": "#121212",
    "text": "#f1f1f1",
    "secondary": "#888",
    "accent": "#1f6feb",
    "card_bg": "#1e1e1e",
    "border": "#2c2c2c"
}

def create_arrow_controls(operator_id):
    def button(direction, label):
        return html.Button(label, id={"type": "move-btn", "oid": operator_id, "dir": direction}, n_clicks=0,
                           style={"width": "40px", "margin": "2px"})

    fire_btn = html.Button("🔥", id={"type": "fire-btn", "oid": operator_id}, n_clicks=0,
                           style={"marginTop": "4px", "padding": "4px 10px", "backgroundColor": "#b22222", "color": "#fff", "border": "none", "borderRadius": "4px", "cursor": "pointer"})

    return html.Div([
        html.Div([button("up", "↑")], style={"textAlign": "center"}),
        html.Div([
            button("left", "←"),
            button("right", "→")
        ], style={"textAlign": "center"}),
        html.Div([button("down", "↓")], style={"textAlign": "center"}),
        html.Div(fire_btn, style={"textAlign": "center"})
    ])


dash_app.layout = html.Div([
    html.Div([
        html.H3("Minekov Dashboard", style={"margin": "0", "color": dark_theme["text"]}),
        html.Button("Ping", id="say-hello", n_clicks=0, style={
            "marginLeft": "auto",
            "marginRight": "10px",
            "padding": "6px 12px",
            "backgroundColor": dark_theme["accent"],
            "color": "#fff",
            "border": "none",
            "borderRadius": "4px",
            "cursor": "pointer"
        }),
        html.Div(id="hello-output", style={"marginLeft": "20px", "color": dark_theme["secondary"]})
    ], style={
        "display": "flex",
        "alignItems": "center",
        "justifyContent": "space-between",
        "padding": "10px 20px",
        "backgroundColor": dark_theme["card_bg"],
        "borderBottom": f"1px solid {dark_theme['border']}"
    }),

    html.Div([
        html.Div([
            html.H4("RLOperators", style={"color": dark_theme["text"], "marginBottom": "10px"}),
            html.Div(id="operator-list")
        ], style={
            "width": "10%",
            "height": "90vh",
            "overflowY": "auto",
            "padding": "10px",
            "backgroundColor": dark_theme["background"],
            "color": dark_theme["text"],
            "borderRight": f"1px solid {dark_theme['border']}",
            "fontSize": "14px"
        }),

        html.Div([
            dcc.Graph(id="scene-graph", style={"height": "90vh"}),
            dcc.Store(id="scene-refresh-trigger", data=0),
            dcc.Store(id="camera-store"),
            dcc.Interval(id="operator-refresh", interval=2000, n_intervals=0),
            dcc.Interval(id="scene-poll", interval=1000, n_intervals=0),
            html.Div(id="dummy", style={"display": "none"})
        ], style={"width": "90%", "display": "inline-block"})
    ], style={"display": "flex", "backgroundColor": dark_theme["background"]})
], style={"backgroundColor": dark_theme["background"], "height": "100vh", "margin": "0"})

@dash_app.callback(Output("hello-output", "children"), Input("say-hello", "n_clicks"), prevent_initial_call=True)
def say_hello(n_clicks):
    global event_loop
    payload = json.dumps({"type": "ping", "msg": "hello"})
    if not connected_websockets:
        return "No Java clients connected."
    for ws in connected_websockets.copy():
        try:
            if event_loop:
                asyncio.run_coroutine_threadsafe(ws.send_text(payload), event_loop)
        except Exception:
            connected_websockets.discard(ws)
    return "Hello sent to Java!"

@dash_app.callback(Output("operator-list", "children"), Input("operator-refresh", "n_intervals"))
def refresh_operator_list(_):
    rows = []
    for oid, data in operator_state.items():
        health = data.get("health", "???")
        x = round(data.get("x", 0))
        y = round(data.get("y", 0))
        z = round(data.get("z", 0))

        rows.append(html.Div([
            html.Div(f"{oid}", style={"color": dark_theme["secondary"], "fontSize": "11px", "marginBottom": "2px"}),
            html.Div(f"XYZ: ({x}, {y}, {z})"),
            html.Div(f"HP: {health}", style={"color": "#ff5555" if isinstance(health, (int, float)) and health < 10 else dark_theme["text"]}),
            create_arrow_controls(oid),
            html.Hr(style={"border": f"0.5px solid {dark_theme['border']}"})
        ], style={"padding": "6px 4px"}))
    return rows

@dash_app.callback(Output("scene-graph", "figure"), Input("scene-refresh-trigger", "data"), State("camera-store", "data"), prevent_initial_call=True)
def refresh_scene(_, camera):
    with volume_lock:
        vol_copy = volume.copy()
    coords = np.argwhere(vol_copy == 1)
    if coords.size == 0:
        coords = np.zeros((1, 3))
    z = coords[:, 1]
    y = coords[:, 0]
    x = coords[:, 2]
    fig = go.Figure(data=go.Scatter3d(x=x, y=y, z=z, mode='markers', marker=dict(size=5, color='gray', symbol='square', opacity=0.8)))
    layout = dict(
        scene=dict(xaxis_title="X", yaxis_title="Z", zaxis_title="Y", aspectmode='manual', aspectratio=dict(x=1, y=1, z=0.1)),
        paper_bgcolor=dark_theme["background"],
        plot_bgcolor=dark_theme["background"],
        font=dict(color=dark_theme["text"]),
        margin=dict(l=0, r=0, b=0, t=30),
    )
    if camera:
        layout["scene"]["camera"] = camera
    fig.update_layout(**layout)
    return fig

import time

@dash_app.callback(Output("dummy", "style"),  # dummy output to trigger
    Input({"type": "fire-btn", "oid": ALL}, "n_clicks_timestamp"),
    State({"type": "fire-btn", "oid": ALL}, "id"),
    prevent_initial_call=True)
def fire_weapon(timestamps, ids):
    global event_loop
    filtered = [(ts, btn_id) for ts, btn_id in zip(timestamps, ids) if ts is not None]
    if not filtered:
        return dash.no_update

    _, btn_id = max(filtered, key=lambda x: x[0])
    oid = btn_id["oid"]

    packet = {
        "type": "fire",
        "id": oid,
        "timestamp": int(time.time() * 1000)
    }

    for ws in connected_websockets.copy():
        try:
            if event_loop:
                asyncio.run_coroutine_threadsafe(ws.send_text(json.dumps(packet)), event_loop)
        except Exception:
            connected_websockets.discard(ws)

    return {"display": "none"}


@dash_app.callback(Output("dummy", "children"),
    Input({"type": "move-btn", "dir": ALL, "oid": ALL}, "n_clicks_timestamp"),
    State({"type": "move-btn", "dir": ALL, "oid": ALL}, "id"),
    prevent_initial_call=True)
def send_movement_events(timestamps, ids):
    global event_loop

    # Zip and filter out unclicked buttons
    filtered = [(ts, btn_id) for ts, btn_id in zip(timestamps, ids) if ts is not None]
    if not filtered:
        return dash.no_update

    # Pick the most recently clicked one
    latest_ts, btn_id = max(filtered, key=lambda x: x[0])

    direction = btn_id["dir"]
    oid = btn_id["oid"]
    angle = {"up": 0, "right": 90, "down": 180, "left": 270}.get(direction, 0)

    packet = {
        "type": "joystick_vector",
        "id": oid,
        "vector": {"x": 0.0, "y": 1.0, "angle": angle},
        "timestamp": int(time.time() * 100) * 10  # rounded to nearest 10ms
    }

    for ws in connected_websockets.copy():
        try:
            if event_loop:
                asyncio.run_coroutine_threadsafe(ws.send_text(json.dumps(packet)), event_loop)
        except Exception:
            connected_websockets.discard(ws)

    return dash.no_update

# ---------------- FastAPI Server ----------------
asgi_app = FastAPI()

@asgi_app.post("/scene")
async def receive_scene(request: Request):
    global volume, latest_update_flag

    print("[/scene] hit", flush=True)

    shape_header = request.headers.get("X-Scene-Shape")
    if not shape_header:
        print("❌ Missing X-Scene-Shape header", flush=True)
        return {"error": "Missing X-Scene-Shape header"}

    try:
        shape = tuple(map(int, shape_header.split(",")))
        print(f"✅ Parsed shape: {shape}", flush=True)
    except Exception as e:
        print(f"❌ Failed to parse shape header '{shape_header}': {e}", flush=True)
        return {"error": f"Invalid shape format: {shape_header}"}

    raw = await request.body()
    print(f"📦 Received {len(raw)} bytes from request body", flush=True)

    arr = np.frombuffer(raw, dtype=np.uint8)
    expected_size = np.prod(shape)

    if arr.size == expected_size:
        with volume_lock:
            volume = arr.reshape(shape)
        latest_update_flag += 1
        print(f"✅ Scene volume accepted: shape={shape} size={arr.size} sum={arr.sum()}", flush=True)
        return {"status": "ok"}
    else:
        print(f"❌ Size mismatch: expected={expected_size}, got={arr.size}", flush=True)
        return {"error": f"Expected {expected_size}, got {arr.size}"}
    
@dash_app.callback(
    Output("scene-refresh-trigger", "data"),
    Input("scene-poll", "n_intervals"),
    State("scene-refresh-trigger", "data")
)
def poll_for_scene_change(_, prev_flag):
    global latest_update_flag
    if latest_update_flag != prev_flag:
        return latest_update_flag
    return dash.no_update

@asgi_app.websocket("/socket")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_websockets.add(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            try:
                payload = json.loads(data)
                if payload.get("type") == "sync_operators":
                    agents = payload.get("agents", [])
                    operator_state.clear()
                    for agent in agents:
                        agent_id = agent.get("id")
                        if agent_id:
                            operator_state[agent_id] = agent
                elif payload.get("type") == "tick":
                    operator_ids = payload.get("operator_ids", [])

                    move_degree, should_shoot = train_state.sample_action()

                    for oid in operator_ids:
                        oid = oid.strip()
                        if not oid:
                            continue

                        if move_degree is not None:
                            move_packet = {
                                "type": "joystick_vector",
                                "id": oid,
                                "vector": {"x": 0.0, "y": 1.0, "angle": move_degree},
                                "timestamp": int(time.time() * 1000)
                            }
                            await websocket.send_text(json.dumps(move_packet))

                        if should_shoot:
                            shoot_packet = {
                                "type": "fire",
                                "id": oid,
                                "timestamp": int(time.time() * 1000)
                            }
                            await websocket.send_text(json.dumps(shoot_packet))
                else:
                    print("Unhandled:", payload)
            except json.JSONDecodeError:
                print("Invalid JSON:", data)
    except Exception as e:
        print("WebSocket closed:", e)
    finally:
        connected_websockets.discard(websocket)

server = WSGIMiddleware(dash_app.server)
asgi_app.mount("/", server)

if __name__ == "__main__":
    import uvicorn

    async def main():
        global event_loop
        config = uvicorn.Config(asgi_app, host="0.0.0.0", port=8050, loop="asyncio")
        server = uvicorn.Server(config)
        await server.serve()

    try:
        event_loop = asyncio.new_event_loop()
        asyncio.set_event_loop(event_loop)
        event_loop.run_until_complete(main())
    except KeyboardInterrupt:
        print("\n[Server shut down cleanly]")
