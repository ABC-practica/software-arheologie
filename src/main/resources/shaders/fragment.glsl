#version 330 core
in vec3 Normal;
in vec3 FragPos;
in vec2 TexCoords;

layout(location = 0) out vec4 FragColor;
layout(location = 1) out vec4 PickingColor;

uniform sampler2D texture1;
uniform vec3 objectIdColor;
uniform int isSelected;

void main()
{
    vec3 lightDir = normalize(vec3(0.2, 0.8, 1.0));

    vec3 norm = normalize(Normal);
    float diff = max(dot(norm, lightDir), 0.5);

    vec4 texColor = texture(texture1, TexCoords);
    vec3 result = diff * texColor.rgb;

    if (isSelected == 1) {
        result = mix(result, vec3(1.0, 0.2, 0.2), 0.3);
    }

    FragColor = vec4(result, 1.0);
    PickingColor = vec4(objectIdColor, 1.0);
}