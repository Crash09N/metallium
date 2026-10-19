#version 120
// Metallium history test pack (Apache-2.0): copies colortex6 (flips it back to its main texture). composite wrote
// colortex6, so the texture.composite.colortex6 override (yellow) must not apply here (Iris's flippedAtLeastOnce).
uniform sampler2D colortex6;
varying vec2 texcoord;

void main() {
	/* DRAWBUFFERS:6 */
	gl_FragData[0] = texture2D(colortex6, texcoord);
}
