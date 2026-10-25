#version 330 core
layout(location = 0) in vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec3 veilPosition;
out vec3 viewPosition;
out vec3 viewNormal;
void main() {
    veilPosition = Position;
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    viewPosition = view.xyz;
    viewNormal = transpose(inverse(mat3(ModelViewMat))) * Position;
    gl_Position = ProjMat * view;
}
