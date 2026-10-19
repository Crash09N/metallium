#version 430 core
// Metallium custom texture test pack (Apache-2.0): more than 16 textures in one pass (Metal has 16 sampler states per
// stage; Bliss's composite4 uses 20). The bottom band turns green if all of them read as expected, red otherwise.
uniform sampler2D colortex0;
uniform sampler2D colortex1;  // never written: Iris clears colortex1 to white
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D colortex5;  // texture.composite.colortex5 = stripes.png (red at u = 0.25)
uniform sampler2D colortex6;
uniform sampler2D colortex7;
uniform sampler2D colortex8;
uniform sampler2D colortex9;
uniform sampler2D colortex10;
uniform sampler2D colortex11;
uniform sampler2D colortex12;
uniform sampler2D colortex13;
uniform sampler2D colortex14;
uniform sampler2D colortex15;
uniform sampler2D depthtex0;
uniform sampler2D noisetex;   // texture.noise = noise.png (green)
in vec2 texcoord;

/* DRAWBUFFERS:0 */
layout(location = 0) out vec4 fragColor;

float red(sampler2D s) {
	return texture(s, vec2(0.25, 0.5)).r;
}

void main() {
	vec3 result = texture(colortex0, texcoord).rgb;
	if (texcoord.y < 0.1) {
		float sum = red(colortex1) + red(colortex2) + red(colortex3) + red(colortex4) + red(colortex5) + red(colortex6) + red(colortex7)
				+ red(colortex8) + red(colortex9) + red(colortex10) + red(colortex11) + red(colortex12) + red(colortex13) + red(colortex14)
				+ texture(colortex15, vec2(0.25, 0.5)).r;
		float depth = texture(depthtex0, vec2(0.5)).r;
		bool ok = abs(sum - 2.0) < 0.05 && texture(noisetex, vec2(0.5)).g > 0.95 && depth >= 0.0 && depth <= 1.0;
		result = ok ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	}
	fragColor = vec4(result, 1.0);
}
