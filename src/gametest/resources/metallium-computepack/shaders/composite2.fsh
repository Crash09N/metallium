#version 430 core
// Metallium compute test pack (MIT): shows what the compute passes produced.
//   top 10 %, left third:   colortex3 from composite1.csh (green = counter, SSBO and .length() checks passed, red = failed)
//   top 10 %, middle third: this fragment program reads SSBO 0 and the 3D custom image (green = both as expected)
//   top 10 %, right third:  composite3.fsh shows colortex5, which this program writes through colorimg5 (green)
//   bottom 10 %: mipmap check - the smallest mip of colortex2 (half magenta, half black) must be about (0.5, 0, 0.5)
//                (sampled inside the magenta half: without a mip chain this reads pure magenta and fails)
//   elsewhere:   magenta where colortex2 holds the compute-written marker (left half), else the scene
const bool colortex2MipmapEnabled = true;
uniform sampler2D colortex0;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler3D volumeSampler;
layout(rgba8) writeonly uniform image2D colorimg5;
layout(std430, binding = 0) buffer TestData {
	vec4 color;
	uint ok;
	uint pad0;
	uint pad1;
	uint pad2;
	uint values[];
} testData;
in vec2 texcoord;

/* DRAWBUFFERS:0 */
layout(location = 0) out vec4 fragColor;

void main() {
	imageStore(colorimg5, ivec2(gl_FragCoord.xy), vec4(0.0, 1.0, 0.0, 1.0));
	vec3 result;
	if (texcoord.y > 0.9) {
		if (texcoord.x < 1.0 / 3.0) {
			result = texture(colortex3, texcoord).rgb;
		} else {
			bool ok = testData.color == vec4(1.0, 0.0, 1.0, 1.0) && testData.values[5] == 5u
					&& distance(texelFetch(volumeSampler, ivec3(1, 2, 3), 0).rgb, vec3(0.0, 1.0, 0.0)) < 0.01
					&& texelFetch(volumeSampler, ivec3(2, 2, 3), 0).g < 0.01;
			result = ok ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
		}
	} else if (texcoord.y < 0.1) {
		vec3 average = textureLod(colortex2, vec2(0.25, 0.5), 100.0).rgb;
		bool mipOk = abs(average.r - 0.5) < 0.15 && abs(average.b - 0.5) < 0.15 && average.g < 0.1;
		result = mipOk ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	} else {
		vec3 marker = texture(colortex2, texcoord).rgb;
		result = distance(marker, vec3(1.0, 0.0, 1.0)) < 0.1 ? vec3(1.0, 0.0, 1.0) : texture(colortex0, texcoord).rgb;
	}
	fragColor = vec4(result, 1.0);
}
