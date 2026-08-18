#version 330 core
layout (location = 0) in vec3 aPos;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

uniform vec3 sliceNormal;
uniform vec3 sliceNearPoint;
uniform vec3 sliceFarPoint;

out float gl_ClipDistance[2];

void main()
{
    vec3 worldPos = (model * vec4(aPos, 1.0)).xyz;
    gl_ClipDistance[0] = dot(worldPos - sliceNearPoint, sliceNormal);
    gl_ClipDistance[1] = dot(sliceFarPoint - worldPos, sliceNormal);
    gl_Position = projection * view * vec4(worldPos, 1.0);
}
