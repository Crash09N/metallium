#version 120
// Nativium compute test pack (MIT, written for Nativium's game tests).
varying vec2 texcoord;

void main() {
	gl_Position = ftransform();
	texcoord = gl_MultiTexCoord0.xy;
}
