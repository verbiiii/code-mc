import dash
import dash_html_components as html
from dash.dependencies import Output
import dash_core_components as dcc
import numpy as np
import plotly.graph_objects as go
from flask import Flask, request
import threading

# Shared volume buffer
scene_shape = (20, 20, 20)  # Z, Y, X
volume = np.zeros(scene_shape, dtype=np.uint8)

# Flask app
flask_server = Flask(__name__)

@flask_server.route("/scene", methods=["POST"])
def receive_scene():
    global volume
    raw = request.data
    arr = np.frombuffer(raw, dtype=np.uint8)
    if arr.size == np.prod(scene_shape):
        volume = arr.reshape(scene_shape)
        print("Scene updated.")
        return "OK", 200
    else:
        return f"Invalid size: got {arr.size}, expected {np.prod(scene_shape)}", 400

# Dash app
app = dash.Dash(__name__, server=flask_server, routes_pathname_prefix="/")

def create_figure():
    coords = np.argwhere(volume == 1)
    if coords.size == 0:
        coords = np.zeros((1, 3))

    fig = go.Figure(data=go.Scatter3d(
        x=coords[:, 2],
        y=coords[:, 1],
        z=coords[:, 0],
        mode='markers',
        marker=dict(size=4, color='gray', opacity=0.8),
    ))

    fig.update_layout(
        scene=dict(
            xaxis_title="X",
            yaxis_title="Y",
            zaxis_title="Z",
            aspectmode='cube',
        ),
        margin=dict(l=0, r=0, b=0, t=30),
    )
    return fig

app.layout = html.Div([
    html.H3("Minekov Scene Visualizer"),
    dcc.Graph(id="scene-graph", figure=create_figure())
])

if __name__ == "__main__":
    print("Server running at http://localhost:8050/")
    app.run(debug=True, port=8050)
