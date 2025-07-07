from dash import Dash, dcc, html, Input, Output, State
import dash
import numpy as np
import plotly.graph_objects as go
from fastapi import FastAPI, Request, WebSocket
from starlette.middleware.wsgi import WSGIMiddleware
from threading import Lock
import json
import asyncio

from joystick import create_joystick_component, register_joystick_callback

# ---------------- State ----------------
event_loop = None
operator_state = {}
connected_websockets = set()
scene_shape = (256, 10, 256)
volume = np.zeros(scene_shape, dtype=np.uint8)
volume_lock = Lock()
latest_update_flag = 0
joysticks_registered = set()

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

# Layout
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
            dcc.Interval(id="operator-refresh", interval=2000, n_intervals=0)
        ], style={"width": "90%", "display": "inline-block"})
    ], style={"display": "flex", "backgroundColor": dark_theme["background"]})
], style={"backgroundColor": dark_theme["background"], "height": "100vh", "margin": "0"})

# ---------------- Callbacks ----------------

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
        jid = f"joystick-{oid}"
        if jid not in joysticks_registered:
            register_joystick_callback(dash_app, jid)
            joysticks_registered.add(jid)
        rows.append(html.Div([
            html.Div(f"{oid}", style={"color": dark_theme["secondary"], "fontSize": "11px", "marginBottom": "2px"}),
            html.Div(f"XYZ: ({x}, {y}, {z})"),
            html.Div(f"HP: {health}", style={"color": "#ff5555" if isinstance(health, (int, float)) and health < 10 else dark_theme["text"]}),
            create_joystick_component(jid),
            html.Hr(style={"border": f"0.5px solid {dark_theme['border']}"})
        ], style={"padding": "6px 4px"}))
    return rows

@dash_app.callback(Output("camera-store", "data"), Input("scene-graph", "relayoutData"), prevent_initial_call=True)
def store_camera(relayout):
    if relayout and "scene.camera" in relayout:
        return relayout["scene.camera"]
    return dash.no_update

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

# ---------------- FastAPI Server ----------------
asgi_app = FastAPI()

@asgi_app.post("/scene")
async def receive_scene(request: Request):
    global volume, latest_update_flag
    raw = await request.body()
    arr = np.frombuffer(raw, dtype=np.uint8)
    if arr.size == np.prod(scene_shape):
        with volume_lock:
            volume = arr.reshape(scene_shape)
        latest_update_flag += 1
        dash_app.callback_map["scene-refresh-trigger.data"]["state"][0]["value"] = latest_update_flag
        return {"status": "ok"}
    return {"error": f"Expected {np.prod(scene_shape)}, got {arr.size}"}

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
