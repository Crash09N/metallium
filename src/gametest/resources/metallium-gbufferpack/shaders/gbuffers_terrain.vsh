#version 120
// Metallium gbuffers test pack (Apache-2.0, written for Metallium's game tests).
varying vec2 texcoord;
varying vec4 glcolor;

void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	glcolor = gl_Color;
}
