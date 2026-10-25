// 3D preview for the cosmetics server's web page (crates/slopclient-cosmetics/src/web):
// the launcher's CapeViewer without React, built into one script by
// `npm run build:viewer` and exposed as `window.SlopViewer`.

import { FlyingAnimation, IdleAnimation, SkinViewer, WalkingAnimation } from "skinview3d";
import { PET_PLACE, animateChroma, buildHat, buildPet, disposeExtra, setTextureSource, thumbnail, type BuiltPet, type HatModel, type PetModel } from "../lib/extras";
import { fallbackSkin } from "../lib/skin";
import { buildAccessory, type AccessoryModel } from "../lib/accessory";
import { SCALES, activeFrames, decode, defaultState, edgeColor, exportCape, frameTime, maxFramesFor, renderFrame } from "../lib/cape";

export type Pose = "walk" | "fly" | "idle";

/** Splits a cape spritesheet (frames stacked vertically) into one canvas per frame. */
export function capeFrames(sheet: CanvasImageSource, scale: number, frames: number): HTMLCanvasElement[] {
  const w = 64 * scale, h = 32 * scale;
  return Array.from({ length: frames }, (_, i) => {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    const ctx = c.getContext("2d")!;
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(sheet, 0, i * h, w, h, 0, 0, w, h);
    return c;
  });
}

/** Player with cape, hat and pet; every setter applies at once. Drag to turn. */
export class Preview {
  private v: SkinViewer;
  private pose: Pose = "walk";
  private slim: boolean | undefined;
  private cape: { frames: HTMLCanvasElement[]; frameTimeMs: number; elytra: boolean } | null = null;
  private capeTimer: ReturnType<typeof setInterval> | undefined;
  private hat: HatModel | null = null;
  private pet: PetModel | null = null;
  private accessory: AccessoryModel | null = null;
  private hatGroup: ReturnType<typeof buildHat> | null = null;
  private accessoryGroup: Awaited<ReturnType<typeof buildAccessory>> | null = null;
  private built: BuiltPet | null = null;
  private extrasVersion = 0;
  private baseZoom = 0.82;
  private frame = 0;

  constructor(canvas: HTMLCanvasElement, width: number, height: number) {
    this.v = new SkinViewer({ canvas, width, height, skin: fallbackSkin() });
    this.v.fov = 40;
    this.v.zoom = this.baseZoom;
    this.v.autoRotate = true;
    this.v.autoRotateSpeed = 0.6;
    // Start looking at the back, where the cape is.
    this.v.playerObject.rotation.y = Math.PI;
    this.v.controls.enableZoom = false;
    this.setPose("walk");
    const tick = (now: number) => {
      const t = now / 50; // game ticks, like the mod
      const pet = this.pet, built = this.built;
      if (built && pet) {
        const walking = pet.tier === "walking" && this.pose === "walk";
        built.update(t, walking ? t * 0.9 : 0, walking ? 1 : 0);
        if (pet.tier === "flying") built.group.position.y = PET_PLACE.flying[1] + Math.sin(t * 0.08) * 1.1;
        if (built.mini && built.mini.skin.map !== this.v.playerObject.skin.map) built.mini.skin.map = this.v.playerObject.skin.map;
      }
      if (this.hatGroup) animateChroma(this.hatGroup, now);
      this.frame = requestAnimationFrame(tick);
    };
    this.frame = requestAnimationFrame(tick);
  }

  setSize(width: number, height: number) {
    this.v.setSize(width, height);
  }

  async setSkin(url: string | null, slim?: boolean) {
    this.slim = slim;
    if (!url) {
      this.v.loadSkin(fallbackSkin());
    } else {
      await this.v.loadSkin(url, slim === undefined ? undefined : { model: slim ? "slim" : "default" });
    }
    // The mini-me follows the arm model: rebuild it.
    if (this.pet?.special === "minime") this.setExtras(this.hat, this.pet, this.accessory);
  }

  setPose(pose: Pose) {
    this.pose = pose;
    this.v.animation = pose === "fly" ? new FlyingAnimation() : pose === "idle" ? new IdleAnimation() : new WalkingAnimation();
    if (pose === "walk" && this.v.animation) this.v.animation.speed = 0.6;
    this.applyZoom();
    this.applyCape();
  }

  /** `null` takes the cape off. */
  setCape(frames: HTMLCanvasElement[] | null, frameTimeMs = 100, elytra = true) {
    this.cape = frames && frames.length ? { frames, frameTimeMs, elytra } : null;
    this.applyCape();
  }

  private applyCape() {
    clearInterval(this.capeTimer);
    const cape = this.cape;
    if (!cape) {
      this.v.loadCape(null);
      return;
    }
    // Flying shows the cape as elytra (when the cape allows it), like in the game.
    const wings = this.pose === "fly" && cape.elytra;
    this.v.loadCape(cape.frames[0], { backEquipment: wings ? "elytra" : "cape" });
    if (cape.frames.length < 2) return;
    let i = 0;
    this.capeTimer = setInterval(() => {
      i = (i + 1) % cape.frames.length;
      const ctx = this.v.capeCanvas.getContext("2d")!;
      ctx.clearRect(0, 0, this.v.capeCanvas.width, this.v.capeCanvas.height);
      ctx.drawImage(cape.frames[i], 0, 0, this.v.capeCanvas.width, this.v.capeCanvas.height);
      // capeTexture is internal to skinview3d; flag it so three.js re-uploads the canvas.
      const tex = (this.v as unknown as { capeTexture: { needsUpdate: boolean } | null }).capeTexture;
      if (tex) tex.needsUpdate = true;
    }, Math.max(40, cape.frameTimeMs));
  }

  /** Hat on the head; the pet on the shoulder, beside the head or walking beside the player; the accessory where it is worn. */
  setExtras(hat: HatModel | null, pet: PetModel | null, accessory: AccessoryModel | null = null) {
    const version = ++this.extrasVersion;
    if (this.accessoryGroup) {
      this.accessoryGroup.removeFromParent();
      disposeExtra(this.accessoryGroup);
      this.accessoryGroup = null;
    }
    if (accessory) {
      buildAccessory(accessory)
        .then((g) => {
          if (version !== this.extrasVersion) return disposeExtra(g);
          (accessory.attach === "head" ? this.v.playerObject.skin.head : this.v.playerObject.skin).add(g);
          this.accessoryGroup = g;
        })
        .catch(() => {
          // A missing texture only hides it in the preview.
        });
    }
    this.hat = hat;
    this.pet = pet;
    this.accessory = accessory;
    if (this.hatGroup) {
      this.hatGroup.removeFromParent();
      disposeExtra(this.hatGroup);
      this.hatGroup = null;
    }
    if (this.built) {
      this.built.group.removeFromParent();
      // The mini-me shares the viewer's skin texture: keep it alive.
      if (!this.built.mini) disposeExtra(this.built.group);
      this.built = null;
    }
    if (hat) {
      this.hatGroup = buildHat(hat);
      this.v.playerObject.skin.head.add(this.hatGroup);
    }
    this.applyZoom();
    if (!pet) return;
    buildPet(pet, this.v.playerObject.skin.map, this.slim)
      .then((b) => {
        if (version !== this.extrasVersion) return disposeExtra(b.group);
        b.group.position.set(...PET_PLACE[pet.tier]);
        this.v.playerObject.skin.add(b.group);
        this.built = b;
      })
      .catch(() => {
        // A missing texture only hides the pet in the preview.
      });
  }

  /** Walking pets stand beside the player and spread wings are wide: zoom out so they fit. */
  private applyZoom() {
    let zoom = this.baseZoom;
    if (this.pet?.tier === "walking") zoom *= 0.78;
    if (this.pose === "fly") zoom *= 0.7;
    this.v.zoom = zoom;
  }

  dispose() {
    cancelAnimationFrame(this.frame);
    clearInterval(this.capeTimer);
    this.v.dispose();
  }
}

declare global {
  interface Window {
    SlopViewer: { Preview: typeof Preview; capeFrames: typeof capeFrames; thumbnail: typeof thumbnail; cape: typeof cape };
  }
}

// Pet textures come from the cosmetics server, like its catalogue.
setTextureSource((file) => `v1/extras/textures/${encodeURIComponent(file)}`);

/** The launcher's Cape Studio conversion: any picture or animation into the cape layout. */
const cape = { SCALES, activeFrames, decode, defaultState, edgeColor, exportCape, frameTime, maxFramesFor, renderFrame };

window.SlopViewer = { Preview, capeFrames, thumbnail, cape };
