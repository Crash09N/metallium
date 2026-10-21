#version 120
// Nativium history test pack (MIT, written for Nativium's game tests): writes the same test pattern (left
// half red, right half blue) into colortex4 and colortex6. composite1 copies colortex6 once more, so composite2 reads
// colortex4 from its alt texture and colortex6 from its main texture (Iris's ping-pong buffers).
varying vec2 texcoord;

void main() {
	vec4 pattern = texcoord.x < 0.5 ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0);
	/* DRAWBUFFERS:46 */
	gl_FragData[0] = pattern;
	gl_FragData[1] = pattern;
}
