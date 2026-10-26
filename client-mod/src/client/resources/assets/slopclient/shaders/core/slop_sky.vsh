#version 330
#extension GL_ARB_separate_shader_objects : require

// Sky box around the camera: the fragment shader only needs the view direction.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;

layout(location = 0) out vec3 direction;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    direction = Position;
}
