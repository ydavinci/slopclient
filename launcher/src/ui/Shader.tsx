import { useEffect, useRef } from "react";

// Flowing violet "main menu" shader, the backdrop every modern client ships.
// Rendered at half resolution, paused while hidden or while a game runs.
const FRAG = `
precision mediump float;
uniform vec2 r;
uniform float t;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float n(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
  return mix(mix(h(i),h(i+vec2(1.,0.)),f.x),mix(h(i+vec2(0.,1.)),h(i+vec2(1.,1.)),f.x),f.y);}
float fbm(vec2 p){float v=0.,a=.5;for(int i=0;i<5;i++){v+=a*n(p);p*=2.02;a*=.5;}return v;}
void main(){
  vec2 uv=gl_FragCoord.xy/r;
  vec2 p=(gl_FragCoord.xy-.5*r)/r.y;
  float tt=t*.045;
  vec2 q=vec2(fbm(p*1.5+tt),fbm(p*1.5-tt+5.2));
  float f=fbm(p*1.9+q*1.9+tt*.6);
  vec3 col=vec3(.034,.03,.054);
  col=mix(col,vec3(.24,.11,.55),smoothstep(.38,.9,f));
  col=mix(col,vec3(.55,.17,.6),smoothstep(.55,1.,f*q.x*1.45));
  float band=abs(p.y+.22*sin(p.x*2.1+tt*7.)+.12*q.y+.08);
  col+=vec3(.6,.45,1.)*.16*exp(-band*16.);
  float band2=abs(p.y-.18*sin(p.x*1.6-tt*5.)-.1*q.x-.32);
  col+=vec3(.9,.4,.95)*.08*exp(-band2*22.);
  col*=1.-.6*length(uv-vec2(.5,.45));
  gl_FragColor=vec4(col,1.);
}`;

const VERT = `attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}`;

export function Shader({ paused = false }: { paused?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  useEffect(() => {
    const el = canvas.current;
    const gl = el?.getContext("webgl", { antialias: false, premultipliedAlpha: false });
    if (!el || !gl) return;

    const compile = (type: number, src: string) => {
      const s = gl.createShader(type)!;
      gl.shaderSource(s, src);
      gl.compileShader(s);
      return s;
    };
    const prog = gl.createProgram()!;
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return;
    gl.useProgram(prog);
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(prog, "p");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    const uRes = gl.getUniformLocation(prog, "r");
    const uTime = gl.getUniformLocation(prog, "t");

    const resize = () => {
      const scale = 0.5;
      el.width = Math.max(1, Math.round(el.clientWidth * scale));
      el.height = Math.max(1, Math.round(el.clientHeight * scale));
      gl.viewport(0, 0, el.width, el.height);
      gl.uniform2f(uRes, el.width, el.height);
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(el);

    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const start = performance.now() - 40_000;
    let frame = 0;
    const draw = (now: number) => {
      gl.uniform1f(uTime, (now - start) / 1000);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    };
    const loop = (now: number) => {
      if (!pausedRef.current && document.visibilityState === "visible") draw(now);
      frame = requestAnimationFrame(loop);
    };
    draw(performance.now());
    if (!reduced) frame = requestAnimationFrame(loop);

    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, []);

  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden>
      {/* CSS fallback shows if WebGL is unavailable. */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_30%_20%,rgb(91_33_182/0.35),transparent_55%),radial-gradient(ellipse_at_80%_70%,rgb(162_28_175/0.22),transparent_50%)]" />
      <canvas ref={canvas} className="absolute inset-0 h-full w-full" />
      {/* Fine grain so the gradients never band. */}
      <div className="absolute inset-0 opacity-[0.06] mix-blend-overlay [background-image:url('data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%22120%22 height=%22120%22><filter id=%22n%22><feTurbulence type=%22fractalNoise%22 baseFrequency=%22.9%22 numOctaves=%222%22/></filter><rect width=%22120%22 height=%22120%22 filter=%22url(%23n)%22/></svg>')]" />
    </div>
  );
}
