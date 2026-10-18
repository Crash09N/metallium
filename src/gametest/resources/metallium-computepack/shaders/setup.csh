#version 430
// Metallium compute test pack (Apache-2.0): runs once after pipeline creation and stores the marker colour in SSBO 0.
layout(local_size_x = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);

layout(std430, binding = 0) buffer TestData {
	vec4 color;
	uint ok;
	uint pad0;
	uint pad1;
	uint pad2;
	uint values[];
} testData;

void main() {
	testData.color = vec4(1.0, 0.0, 1.0, 1.0);
	for (int i = 0; i < testData.values.length(); i++) {
		testData.values[i] = uint(i);
	}
}
