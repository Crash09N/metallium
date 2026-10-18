#version 120
// Metallium shadow test pack (Apache-2.0, written for Metallium's game tests): shadow casters from the sun's view.
varying vec2 texcoord;

void main() {
	gl_Position = ftransform();
	texcoord = gl_MultiTexCoord0.xy;
}
