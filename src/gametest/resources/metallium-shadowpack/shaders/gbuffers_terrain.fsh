#version 120
// Metallium shadow test pack (Apache-2.0, written for Metallium's game tests): terrain albedo into colortex0.
uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec4 glcolor;

void main() {
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = texture2D(gtexture, texcoord) * glcolor;
}
