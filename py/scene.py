import dash
import dash_html_components as html
from dash.dependencies import Input, Output, State
import dash_core_components as dcc
import numpy as np
import plotly.graph_objects as go
from flask import Flask, request
import threading

scene_shape = (256, 10, 256)  # Z, Y, X
volume = np.zeros(scene_shape, dtype=np.uint8)
volume_lock = threading.Lock()
new_data_flag = False

flask_server = Flask(__name__)

@flask_server.route("/scene", methods=["POST"])
def receive_scene():
    global volume, new_data_flag
    raw = request.data
    arr = np.frombuffer(raw, dtype=np.uint8)
    if arr.size == np.prod(scene_shape):
        with volume_lock:
            volume = arr.reshape(scene_shape)
            new_data_flag = True
        print("Scene updated.")
        return "OK", 200
    else:
        return f"Invalid size: got {arr.size}, expected {np.prod(scene_shape)}", 400

app = dash.Dash(__name__, server=flask_server, routes_pathname_prefix="/")

app.layout = html.Div([
    dcc.Graph(id="scene-graph", style={"width": "100vw", "height": "100vh"}),
    dcc.Interval(id="refresh-timer", interval=500, n_intervals=0),
    dcc.Store(id="camera-store"),
], style={"margin": 0, "padding": 0, "overflow": "hidden"})


@app.callback(
    Output("camera-store", "data"),
    Input("scene-graph", "relayoutData"),
    prevent_initial_call=True
)
def store_camera(relayout):
    if relayout and "scene.camera" in relayout:
        return relayout["scene.camera"]
    return dash.no_update

@app.callback(
    Output("scene-graph", "figure"),
    Input("refresh-timer", "n_intervals"),
    State("camera-store", "data")
)
def refresh_scene(_, camera):
    global new_data_flag
    with volume_lock:
        if not new_data_flag:
            raise dash.exceptions.PreventUpdate
        new_data_flag = False
        vol_copy = volume.copy()

    coords = np.argwhere(vol_copy == 1)
    if coords.size == 0:
        coords = np.zeros((1, 3))

    # Swap Minecraft Y (vertical) to Z (Plotly vertical)
    z = coords[:, 1]  # Y (Minecraft up) → Z (Plotly up)
    y = coords[:, 0]  # Z (Minecraft depth) → Y
    x = coords[:, 2]  # X (unchanged)

    fig = go.Figure(data=go.Scatter3d(
        x=x,
        y=y,
        z=z,
        mode='markers',
        marker=dict(
            size=5,
            color='gray',
            symbol='square',
            opacity=0.8,
        ),
    ))

    layout = dict(
        scene=dict(
            xaxis_title="X",  # Minecraft X
            yaxis_title="Z",  # Minecraft Z
            zaxis_title="Y",  # Minecraft Y (up)
            aspectmode='manual',
            aspectratio=dict(x=1, y=1, z=0.1),  # Z is now vertical → squash that
        ),
        margin=dict(l=0, r=0, b=0, t=30),
    )

    if camera:
        layout["scene"]["camera"] = camera

    fig.update_layout(**layout)
    return fig


if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=8050)
