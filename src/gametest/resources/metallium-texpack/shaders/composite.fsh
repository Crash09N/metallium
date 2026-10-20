#version 430 core
// Metallium custom texture test pack (MIT): four checks in the middle band of the screen, one per quarter
// (green = pass, red = fail): PNG stage override, raw 3D texture, custom noise texture, size.buffer.
uniform sampler2D colortex0;
uniform sampler2D colortex5; // texture.composite.colortex5 = stripes.png (2x1: red, blue; nearest, repeat)
uniform sampler3D depthtex2; // raw 3D texture: texel (1,1,1) = (0, 1, 0.5), all others (1, 0, 0)
uniform sampler2D noisetex;  // texture.noise = noise.png (1x1 green)
uniform sampler2D colortex4; // size.buffer.colortex4 = 64 32
in vec2 texcoord;

/* DRAWBUFFERS:0 */
layout(location = 0) out vec4 fragColor;

bool near(vec3 a, vec3 b) {
	return all(lessThan(abs(a - b), vec3(0.05)));
}

void main() {
	vec3 result = texture(colortex0, texcoord).rgb;
	if (texcoord.y > 0.3 && texcoord.y < 0.7) {
		bool ok;
		if (texcoord.x < 0.25) {
			// 1.75 wraps to 0.75: repeat addressing (no clamp in the PNG's properties).
			ok = near(texture(colortex5, vec2(0.25, 0.5)).rgb, vec3(1.0, 0.0, 0.0)) && near(texture(colortex5, vec2(1.75, 0.5)).rgb, vec3(0.0, 0.0, 1.0));
		} else if (texcoord.x < 0.5) {
			ok = near(texture(depthtex2, vec3(0.75)).rgb, vec3(0.0, 1.0, 0.5)) && near(texture(depthtex2, vec3(0.25, 0.75, 0.75)).rgb, vec3(1.0, 0.0, 0.0));
		} else if (texcoord.x < 0.75) {
			ok = near(texture(noisetex, vec2(0.5)).rgb, vec3(0.0, 1.0, 0.0));
		} else {
			ok = textureSize(colortex4, 0) == ivec2(64, 32);
		}
		result = ok ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	}
	fragColor = vec4(result, 1.0);
}
