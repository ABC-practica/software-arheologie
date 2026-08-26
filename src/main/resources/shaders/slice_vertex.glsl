#version 330 core
layout (location = 0) in vec3 aPos;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

uniform vec3 sliceNormal;
uniform vec3 slicePoint;

out float gl_ClipDistance[1];

void main()
{
    vec3 worldPos = (model * vec4(aPos, 1.0)).xyz;
    gl_ClipDistance[0] = -dot(worldPos - slicePoint, sliceNormal);
    gl_Position = projection * view * vec4(worldPos, 1.0);
}