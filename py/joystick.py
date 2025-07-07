from dash import Dash, html, dcc, Output, Input

def create_joystick_component(joystick_id="joystick"):
    return html.Div([
        html.Canvas(id=f"{joystick_id}-canvas", width=100, height=100, style={
            "backgroundColor": "#1e1e1e",
            "border": "2px solid #2c2c2c",
            "borderRadius": "50%",
            "display": "block",
            "margin": "auto"
        }),
        dcc.Store(id=f"{joystick_id}-store", data=0),
        html.Div(id=f"{joystick_id}-output", style={
            "textAlign": "center",
            "color": "#ccc",
            "marginTop": "10px",
            "whiteSpace": "pre-line"
        })
    ])

def register_joystick_callback(app, joystick_id):
    canvas_id = f"{joystick_id}-canvas"
    output_id = f"{joystick_id}-output"
    store_id = f"{joystick_id}-store"

    app.clientside_callback(
        f"""
        function(n) {{
            setTimeout(() => {{
                const canvas = document.getElementById("{canvas_id}");
                const output = document.getElementById("{output_id}");
                if (!canvas || !output) return;

                const ctx = canvas.getContext("2d");
                const radius = 15;
                const center = {{ x: canvas.width / 2, y: canvas.height / 2 }};
                let x = center.x;
                let y = center.y;
                let dragging = false;

                function draw(showAngle = true) {{
                    ctx.clearRect(0, 0, canvas.width, canvas.height);
                    ctx.beginPath();
                    ctx.arc(x, y, radius, 0, 2 * Math.PI);
                    ctx.fillStyle = "#7CFC00";
                    ctx.fill();

                    const dx = x - center.x;
                    const dy = y - center.y;
                    const maxDist = canvas.width / 2 - radius;
                    const normX = +(dx / maxDist).toFixed(1);
                    const normY = +(dy / maxDist).toFixed(1);

                    if (dx === 0 && dy === 0) {{
                        output.textContent = `Vector: (0.0, 0.0)\\nAngle: None`;
                    }} else {{
                        let angleDeg = Math.atan2(dy, dx) * (180 / Math.PI);
                        angleDeg = (angleDeg + 360) % 360;
                        angleDeg = (angleDeg + 90) % 360;
                        output.textContent = `Vector: (${{normX}}, ${{normY}})\\nAngle: ${{angleDeg.toFixed(1)}}°`;
                    }}
                }}

                function animateReturn() {{
                    const dx = center.x - x;
                    const dy = center.y - y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 1) {{
                        x = center.x;
                        y = center.y;
                        draw(false);
                        return;
                    }}
                    x += dx * 0.2;
                    y += dy * 0.2;
                    draw(false);
                    requestAnimationFrame(animateReturn);
                }}

                canvas.addEventListener('mousedown', e => {{
                    const rect = canvas.getBoundingClientRect();
                    const mx = e.clientX - rect.left;
                    const my = e.clientY - rect.top;
                    const dx = mx - center.x;
                    const dy = my - center.y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    const maxDist = canvas.width / 2 - radius;
                    if (dist <= canvas.width / 2) {{
                        dragging = true;
                        if (dist > maxDist) {{
                            const scale = maxDist / dist;
                            x = center.x + dx * scale;
                            y = center.y + dy * scale;
                        }} else {{
                            x = mx;
                            y = my;
                        }}
                        draw();
                    }}
                }});

                window.addEventListener('mousemove', e => {{
                    if (!dragging) return;
                    const rect = canvas.getBoundingClientRect();
                    const mx = e.clientX - rect.left;
                    const my = e.clientY - rect.top;
                    const dx = mx - center.x;
                    const dy = my - center.y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    const maxDist = canvas.width / 2 - radius;
                    if (dist > maxDist) {{
                        const scale = maxDist / dist;
                        x = center.x + dx * scale;
                        y = center.y + dy * scale;
                    }} else {{
                        x = mx;
                        y = my;
                    }}
                    draw();
                }});

                window.addEventListener('mouseup', () => {{
                    if (dragging) {{
                        dragging = false;
                        animateReturn();
                    }}
                }});

                draw();
            }}, 0);
            return window.dash_clientside.no_update;
        }}
        """,
        Output(store_id, "data"),
        Input(store_id, "data")
    )


# Run it
if __name__ == "__main__":
    app = Dash(__name__)
    app.layout = html.Div([
        html.H3("Joystick Demo", style={"color": "white", "textAlign": "center"}),

        html.Div([
            html.Div([
                html.H4("Joystick A", style={"color": "white", "textAlign": "center"}),
                create_joystick_component("joyA"),
            ], style={"margin": "20px"}),

            html.Div([
                html.H4("Joystick B", style={"color": "white", "textAlign": "center"}),
                create_joystick_component("joyB"),
            ], style={"margin": "20px"})
        ], style={"display": "flex", "justifyContent": "center"}),

    ], style={"backgroundColor": "#121212", "height": "100vh", "paddingTop": "40px"})

    register_joystick_callback(app, "joyA")
    register_joystick_callback(app, "joyB")

    app.run(debug=True)
