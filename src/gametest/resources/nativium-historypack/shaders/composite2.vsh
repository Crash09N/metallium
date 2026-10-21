#version 330 compatibility
// Nativium history test pack (MIT, written for Nativium's game tests).
out vec2 texcoord;

void main() {
	gl_Position = ftransform();
	texcoord = gl_MultiTexCoord0.xy;
}
