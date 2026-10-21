#version 120
// Nativium shadow test pack (MIT, written for Nativium's game tests): terrain albedo into colortex0.
uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec4 glcolor;

void main() {
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = texture2D(gtexture, texcoord) * glcolor;
}
