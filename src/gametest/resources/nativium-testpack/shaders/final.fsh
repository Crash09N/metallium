#version 120
// Nativium test pack: copies colortex0 to the screen.
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
	gl_FragColor = vec4(texture2D(colortex0, texcoord).rgb, 1.0);
}
