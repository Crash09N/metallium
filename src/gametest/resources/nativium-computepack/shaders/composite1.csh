#version 430
// Nativium compute test pack (MIT): a compute-only pass (no composite1.fsh). Checks that composite.csh ran for
// every pixel (atomic counter), that the setup pass filled the SSBO and that .length() of its runtime array is right,
// then paints colortex3 green (all good) or red. workGroupsRender: one thread per pixel.
layout(local_size_x = 16, local_size_y = 16) in;
const vec2 workGroupsRender = vec2(1.0, 1.0);

layout(rgba8) writeonly uniform image2D colorimg3;
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
	uint counted = imageLoad(counterImg, ivec2(0, 0)).r;
	bool ok = counted == uint(viewWidth) * uint(viewHeight)
			&& testData.values.length() == 8 && testData.values[7] == 7u
			&& testData.color == vec4(1.0, 0.0, 1.0, 1.0);
	imageStore(colorimg3, p, ok ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0));
}
