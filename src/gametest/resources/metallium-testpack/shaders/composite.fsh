#version 120
// Metallium test pack: inverts the scene colours. viewWidth/viewHeight must be delivered (> 0), otherwise the output is
// magenta, which the game test treats as a failure. frameTimeCounter goes through the same uniform path.
uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;
varying vec2 texcoord;

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	vec3 inverted = vec3(1.0) - color;
	float time = fract(frameTimeCounter) * 0.0;
	vec3 result = (viewWidth > 0.5 && viewHeight > 0.5) ? inverted + time : vec3(1.0, 0.0, 1.0);
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(result, 1.0);
}
