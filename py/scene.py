from dash import Dash, dcc, html, Input, Output, State
import dash
import numpy as np
import plotly.graph_objects as go
from fastapi import FastAPI, Request, WebSocket
from starlette.middleware.wsgi import WSGIMiddleware
from starlette.responses import Response
from threading import Lock
import json

scene_shape = (256, 10, 256)
volume = np.zeros(scene_shape, dtype=np.uint8)
volume_lock = Lock()
connected_websockets = set()
latest_update_flag = 0

# ---------------- Dash (WSGI App) ----------------
dash_app = Dash(__name__, routes_pathname_prefix="/")
server = WSGIMiddleware(dash_app.server)

dash_app.layout = html.Div([
    html.Div([
        html.H3("Controls"),
        html.Button("Say Hello", id="say-hello", n_clicks=0),
        html.Div(id="hello-output")
    ], style={"width": "10%", "display": "inline-block", "verticalAlign": "top", "padding": "10px"}),

    html.Div([
        dcc.Graph(id="scene-graph", style={"height": "100vh"}),
        dcc.Store(id="scene-refresh-trigger", data=0),
        dcc.Store(id="camera-store"),
    ], style={"width": "90%", "display": "inline-block"})
])

@dash_app.callback(
    Output("hello-output", "children"),
    Input("say-hello", "n_clicks"),
    prevent_initial_call=True
)
def say_hello(n_clicks):
    payload = json.dumps({"type": "ping", "msg": "hello"})
    for ws in connected_websockets.copy():
        try:
            import asyncio
            asyncio.get_event_loop().call_soon_threadsafe(
                lambda: asyncio.create_task(ws.send_text(payload))
            )
        except Exception:
            connected_websockets.discard(ws)
    return "Hello sent to Java!"

@dash_app.callback(
    Output("camera-store", "data"),
    Input("scene-graph", "relayoutData"),
    prevent_initial_call=True
)
def store_camera(relayout):
    if relayout and "scene.camera" in relayout:
        return relayout["scene.camera"]
    return dash.no_update

@dash_app.callback(
    Output("scene-graph", "figure"),
    Input("scene-refresh-trigger", "data"),
    State("camera-store", "data"),
    prevent_initial_call=True
)
def refresh_scene(_, camera):
    with volume_lock:
        vol_copy = volume.copy()

    coords = np.argwhere(vol_copy == 1)
    if coords.size == 0:
        coords = np.zeros((1, 3))

    z = coords[:, 1]
    y = coords[:, 0]
    x = coords[:, 2]

    fig = go.Figure(data=go.Scatter3d(
        x=x, y=y, z=z,
        mode='markers',
        marker=dict(size=5, color='gray', symbol='square', opacity=0.8),
    ))

    layout = dict(
        scene=dict(
            xaxis_title="X",
            yaxis_title="Z",
            zaxis_title="Y",
            aspectmode='manual',
            aspectratio=dict(x=1, y=1, z=0.1),
        ),
        margin=dict(l=0, r=0, b=0, t=30),
    )

    if camera:
        layout["scene"]["camera"] = camera

    fig.update_layout(**layout)
    return fig

# ---------------- ASGI App (manual dispatch) ----------------
asgi_app = FastAPI()

@asgi_app.post("/scene")
async def receive_scene(request: Request):
    global volume, latest_update_flag
    raw = await request.body()
    arr = np.frombuffer(raw, dtype=np.uint8)
    if arr.size == np.prod(scene_shape):
        with volume_lock:
            volume = arr.reshape(scene_shape)
        print("Scene updated.")
        latest_update_flag += 1
        dash_app.callback_map["scene-refresh-trigger.data"]["state"][0]["value"] = latest_update_flag
        return {"status": "ok"}
    else:
        return {"error": f"Expected {np.prod(scene_shape)}, got {arr.size}"}

@asgi_app.websocket("/socket")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()
    print("✅ Java connected")
    connected_websockets.add(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            print("Received from Java:", data)
    except Exception as e:
        print("WebSocket closed:", e)
    finally:
        connected_websockets.discard(websocket)

server = WSGIMiddleware(dash_app.server)
asgi_app.mount("/", server)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(asgi_app, host="0.0.0.0", port=8050)
