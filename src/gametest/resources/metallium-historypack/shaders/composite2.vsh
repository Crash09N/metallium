#version 330 compatibility
// Metallium history test pack (Apache-2.0, written for Metallium's game tests).
out vec2 texcoord;

void main() {
	gl_Position = ftransform();
	texcoord = gl_MultiTexCoord0.xy;
}
