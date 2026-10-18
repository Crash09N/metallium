#version 120
// Metallium compute test pack (Apache-2.0): shows what the compute passes produced.
//   top 10 %:    colortex3 from composite1.csh (green = counter, SSBO and .length() checks passed, red = failed)
//   bottom 10 %: mipmap check - the smallest mip of colortex2 (half magenta, half black) must be about (0.5, 0, 0.5)
//                (sampled inside the magenta half: without a mip chain this reads pure magenta and fails)
//   elsewhere:   magenta where colortex2 holds the compute-written marker (left half), else the scene
const bool colortex2MipmapEnabled = true;
uniform sampler2D colortex0;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
varying vec2 texcoord;

void main() {
	vec3 result;
	if (texcoord.y > 0.9) {
		result = texture2D(colortex3, texcoord).rgb;
	} else if (texcoord.y < 0.1) {
		vec3 average = texture2DLod(colortex2, vec2(0.25, 0.5), 100.0).rgb;
		bool mipOk = abs(average.r - 0.5) < 0.15 && abs(average.b - 0.5) < 0.15 && average.g < 0.1;
		result = mipOk ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	} else {
		vec3 marker = texture2D(colortex2, texcoord).rgb;
		result = distance(marker, vec3(1.0, 0.0, 1.0)) < 0.1 ? vec3(1.0, 0.0, 1.0) : texture2D(colortex0, texcoord).rgb;
	}
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(result, 1.0);
}
