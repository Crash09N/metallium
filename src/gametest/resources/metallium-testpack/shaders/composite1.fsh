#version 120
// Metallium test pack (MIT, written for Metallium's game tests): checks a pack blend override on a fullscreen
// pass (shaders.properties: blend.composite1=SRC_ALPHA ZERO ONE ZERO). The pass outputs twice the (already inverted)
// colour with alpha 0.5; only with that blend state does the target receive the colour unchanged (0.5 * 2 * colour,
// destination ignored). Without blending the image would be twice as bright, which fails the inverted-pixel check.
// colortex0 is a float target so the doubled colour is not clamped before blending.
uniform sampler2D colortex0;
varying vec2 texcoord;

/*
const int colortex0Format = RGBA16F;
*/

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(color * 2.0, 0.5);
}
