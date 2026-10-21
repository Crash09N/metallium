#version 120
// Nativium gbuffers test pack: pixels where gbuffers_terrain left its marker in colortex1 become magenta, all others
// show colortex0 (sky etc. drawn by the vanilla shaders into the gbuffers). The game test counts magenta pixels.
uniform sampler2D colortex0;
uniform sampler2D colortex1;
varying vec2 texcoord;

void main() {
	vec4 marker = texture2D(colortex1, texcoord);
	vec3 color = texture2D(colortex0, texcoord).rgb;
	bool terrain = marker.g > 0.9 && marker.r < 0.1 && marker.b < 0.1;
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(terrain ? vec3(1.0, 0.0, 1.0) : color, 1.0);
}
