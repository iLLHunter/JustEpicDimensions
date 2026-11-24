#version 150

// JED's darkening pass for a tinted sun or moon, drawn with blend ZERO, ONE_MINUS_SRC_COLOR. Each texel darkens the
// sky by its brightness times its own alpha, the share the native draw then adds back in the tint colour, so soft or
// semi-transparent texture edges blend into the sky instead of leaving a dark rim. ColorModulator.rgb carries the
// rain fade.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = vec4(color.rgb * color.a * ColorModulator.rgb, 1.0);
}
