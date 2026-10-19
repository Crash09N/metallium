#version 120
// Metallium history test pack (Apache-2.0): copies colortex6 (flips it back to its main texture).
uniform sampler2D colortex6;
varying vec2 texcoord;

void main() {
	/* DRAWBUFFERS:6 */
	gl_FragData[0] = texture2D(colortex6, texcoord);
}
