#version 430
// Metallium compute test pack (Apache-2.0): one thread per pixel (default work groups). The left half of colortex2
// gets the SSBO marker colour through colorimg2; every pixel counts itself in the custom image with an atomic add.
layout(local_size_x = 8, local_size_y = 8) in;

layout(rgba8) uniform image2D colorimg2;
layout(r32ui) uniform uimage2D counterImg;
layout(std430, binding = 0) buffer TestData {
	vec4 color;
	uint ok;
	uint pad0;
	uint pad1;
	uint pad2;
	uint values[];
} testData;
uniform float viewWidth;
uniform float viewHeight;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	if (p.x >= int(viewWidth) || p.y >= int(viewHeight)) {
		return;
	}
	imageAtomicAdd(counterImg, ivec2(0, 0), 1u);
	if (p.x < int(viewWidth) / 2) {
		imageStore(colorimg2, p, testData.color);
	}
}
