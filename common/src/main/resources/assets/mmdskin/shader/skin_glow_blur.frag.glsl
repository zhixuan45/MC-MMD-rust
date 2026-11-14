#version 330 core
in vec2 uv;
uniform sampler2D Source;
uniform vec2 Direction;
layout(location = 0) out vec4 Color;
void main() {
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int tap = -6; tap <= 6; tap++) {
        float weight = exp(-float(tap * tap) / 18.0);
        sum += texture(Source, uv + Direction * float(tap)) * weight;
        total += weight;
    }
    Color = sum / total;
}
