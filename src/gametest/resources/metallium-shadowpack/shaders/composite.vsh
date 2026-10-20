#version 120
// Metallium shadow test pack (MIT, written for Metallium's game tests).
varying vec2 texcoord;

void main() {
	gl_Position = ftransform();
	texcoord = gl_MultiTexCoord0.xy;
}
