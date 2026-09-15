#!/usr/bin/env python3
"""
sherdtool.py — Archaeological ceramic sherd analysis from 3D scans.

What it does
------------
For each 3D mesh (.obj / .ply / .stl / .glb) of a pottery sherd, sherdtool:
  1. Auto-finds the vessel's axis of symmetry using multi-slice circle fits
     across the full sherd curvature (robust even for large-diameter vessels
     where only a small arc is preserved).
  2. Extracts a meridional cross-section.
  3. Reconstructs the exterior profile r(h) using circle fits at multiple heights.
  4. Renders an archaeological-plate-style output: section silhouette + rim plan
     + naturalistic exterior view + measurements.
  5. Outputs PNG + SVG per sherd, plus a CSV summary and an HTML gallery.

Usage
-----
    # Batch over a folder (most common):
    python sherdtool.py path/to/meshes/ -o output/

    # Single mesh, non-interactive:
    python sherdtool.py TRI100300d_Smart_fusion_1.obj -o output/ --unit cm

    # With interactive axis tweaking for difficult sherds:
    python sherdtool.py hard_sherd.obj --interactive

    # Full options:
    python sherdtool.py meshes/ -o out/ --unit cm --axis auto --report --verbose

Dependencies (pip install ...):
    trimesh numpy scipy matplotlib shapely

Author: built with Claude for M. Forte, GW / Duke.
"""

import argparse
import csv
import json
import sys
import traceback
import warnings
from collections import Counter, defaultdict
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional

import numpy as np
import trimesh
import matplotlib.pyplot as plt
from matplotlib.collections import PolyCollection
from matplotlib.widgets import Slider, Button
from scipy.signal import savgol_filter
from shapely.geometry import Polygon

warnings.filterwarnings("ignore", category=UserWarning)
plt.rcParams["figure.max_open_warning"] = 0


# =============================================================================
# Build identifier — the GUI logs this on startup so we can confirm you're
# actually running the patched file (and not a cached copy from elsewhere).
# =============================================================================
_VERSION_TAG = "fast-clip-2026-04-28"


# =============================================================================
# Per-stage timing dict. After each call to render_plate, this dict holds
# the elapsed time of each major stage in milliseconds. The GUI reads it
# and shows a one-line summary in the log pane so we can see where the
# time actually goes on your hardware.
# =============================================================================
_LAST_TIMING = {}


# =============================================================================
# Last rendered sherd bounding box, in plate data coordinates. Set by
# render_plate after the sherd image has been positioned, scaled, rotated,
# and tweaked. Format: (xmin, xmax, ymin, ymax). Read by the GUI to anchor
# user-drawn strokes to the sherd image — strokes are stored in normalized
# 0..1 sherd-local coordinates and converted to plate coords on redraw
# using whatever bbox the latest render produced.
# =============================================================================
_LAST_SHERD_BBOX = None


# =============================================================================
# Module-level helpers (added 2026-04)
#   _sample_face_colors_from_mesh : texture/UV/vertex-colour sampler
#   _rasterize_polys_gpu          : GPU rasterisation of pre-shaded polygons
#   _GPU_AVAILABLE                : one-shot probe for PyVista/OpenGL
# =============================================================================

# Cached probe result. None = not yet checked, True = OK, False = failed.
_GPU_AVAILABLE = None


def _check_gpu_available():
    """Return True if PyVista can produce a screenshot in this process.

    Cached after the first call. We probe by rendering a 32×32 image of a
    tiny mesh; that's fast on real GPUs (≈10 ms) and reliable as a smoke
    test. On Windows with NVIDIA drivers, PyVista's VTK backend talks to
    the OpenGL ICD and rasterisation runs on the card."""
    global _GPU_AVAILABLE
    if _GPU_AVAILABLE is not None:
        return _GPU_AVAILABLE
    try:
        import pyvista as pv
        pv.OFF_SCREEN = True
        verts = np.array([[0, 0, 0], [1, 0, 0], [0, 1, 0]], dtype=float)
        faces = np.array([3, 0, 1, 2], dtype=np.int64)
        m = pv.PolyData(verts, faces)
        p = pv.Plotter(off_screen=True, window_size=(32, 32), lighting='none')
        p.add_mesh(m, color='red', lighting=False)
        p.camera.parallel_projection = True
        _ = p.screenshot(return_img=True)
        p.close()
        _GPU_AVAILABLE = True
    except Exception as e:
        # PyVista not installed, no OpenGL, or VTK runtime issue. Caller
        # silently falls back to the CPU PolyCollection path.
        _GPU_AVAILABLE = False
    return _GPU_AVAILABLE


def _rasterize_polys_gpu(polys, colors, extent_xy, image_size_px, alpha=0.85,
                          bulk_xy=None, bulk_colors=None,
                          straddle_polys=None, straddle_colors=None):
    """Rasterise a list of pre-shaded 2D polygons through PyVista/OpenGL.

    Two calling conventions:

    1. **Fast path** (vectorised). Pass bulk_xy as an (N, 3, 2) ndarray of
       triangles, bulk_colors as an (N, 3) ndarray of RGB in [0, 1], and
       optionally straddle_polys / straddle_colors as a small Python list
       of irregular polygons (variable vertex count). The fast path
       avoids any 500k-iteration Python loop and is roughly 10× faster
       than the slow path for the same polygon count.

    2. **Slow path** (compatible). Pass `polys` as a Python list of (N_i, 2)
       arrays and `colors` as a list of [r, g, b]. Used as a fallback when
       the caller doesn't have the bulk ndarray handy. Still much faster
       than CPU PolyCollection but ~10× slower than the fast path on big
       meshes because of Python-level iteration to build the VTK mesh.

    Parameters
    ----------
    extent_xy  : (xmin, xmax, ymin, ymax) in plate coordinates.
    image_size_px : (width, height) in pixels.
    alpha      : multiplied into the alpha channel.

    Returns
    -------
    rgba : (H, W, 4) uint8 array, or None on any failure.
    """
    if not _check_gpu_available():
        return None
    try:
        import pyvista as pv
        pv.OFF_SCREEN = True

        # Build the PyVista PolyData from either fast path or slow path.
        if bulk_xy is not None and len(bulk_xy):
            # ── FAST PATH: vectorised mesh build ──
            n_bulk = len(bulk_xy)              # all are 3-vertex triangles
            n_straddle = len(straddle_polys) if straddle_polys else 0
            # Flatten bulk verts: (N, 3, 2) -> (N*3, 2)
            verts_2d_bulk = bulk_xy.reshape(-1, 2)
            # Vertices of straddle polys (variable count each)
            straddle_n_each = (np.array([len(p) for p in straddle_polys])
                               if n_straddle else np.empty(0, dtype=int))
            straddle_total = int(straddle_n_each.sum())
            total_verts = n_bulk * 3 + straddle_total
            verts3 = np.empty((total_verts, 3), dtype=np.float64)
            verts3[:n_bulk*3, 0:2] = verts_2d_bulk
            verts3[:, 2] = 0.0
            v_off = n_bulk * 3
            for p in straddle_polys or []:
                np_ = len(p)
                verts3[v_off:v_off+np_, 0:2] = p
                v_off += np_
            # Face connectivity: bulk = repeating [3, i*3, i*3+1, i*3+2]
            n_polys = n_bulk + n_straddle
            face_conn = np.empty(n_bulk*4 + (straddle_total + n_straddle),
                                  dtype=np.int64)
            # Bulk: vectorised connectivity
            if n_bulk:
                bulk_face = face_conn[:n_bulk*4].reshape(n_bulk, 4)
                bulk_face[:, 0] = 3
                base = np.arange(n_bulk) * 3
                bulk_face[:, 1] = base
                bulk_face[:, 2] = base + 1
                bulk_face[:, 3] = base + 2
            # Straddle: small loop (only ~hundreds of items)
            c_off = n_bulk * 4
            v_off = n_bulk * 3
            for i_p, p in enumerate(straddle_polys or []):
                n_ = len(p)
                face_conn[c_off] = n_
                face_conn[c_off+1:c_off+1+n_] = np.arange(v_off, v_off+n_)
                v_off += n_
                c_off += 1 + n_
            # Cell colors: stack bulk + straddle
            if n_bulk:
                cell_rgb_bulk = (np.clip(bulk_colors, 0, 1) * 255).astype(np.uint8)
            else:
                cell_rgb_bulk = np.empty((0, 3), dtype=np.uint8)
            if n_straddle:
                stx = np.asarray(straddle_colors, dtype=np.float64)
                cell_rgb_straddle = (np.clip(stx, 0, 1) * 255).astype(np.uint8)
                cell_rgb = np.concatenate([cell_rgb_bulk, cell_rgb_straddle])
            else:
                cell_rgb = cell_rgb_bulk
        else:
            # ── SLOW PATH: original list-iteration build (backward compat) ──
            if not polys:
                return None
            n_polys = len(polys)
            total_verts = sum(len(p) for p in polys)
            verts3 = np.empty((total_verts, 3), dtype=np.float64)
            face_conn = np.empty(total_verts + n_polys, dtype=np.int64)
            cell_rgb = np.empty((n_polys, 3), dtype=np.uint8)
            v_off = 0; c_off = 0
            for i, p in enumerate(polys):
                n = len(p)
                verts3[v_off:v_off+n, 0:2] = p
                verts3[v_off:v_off+n, 2] = 0.0
                face_conn[c_off] = n
                face_conn[c_off+1:c_off+1+n] = np.arange(v_off, v_off+n)
                v_off += n
                c_off += 1 + n
                r, g, b = colors[i][:3]
                cell_rgb[i] = (max(0.0, min(1.0, r)) * 255,
                               max(0.0, min(1.0, g)) * 255,
                               max(0.0, min(1.0, b)) * 255)

        pmesh = pv.PolyData(verts3, face_conn)
        pmesh.cell_data['rgb'] = cell_rgb

        xmin, xmax, ymin, ymax = extent_xy
        width_px, height_px = image_size_px

        def _make_plotter(bg_color):
            p = pv.Plotter(off_screen=True,
                           window_size=(int(width_px), int(height_px)),
                           lighting='none')
            p.set_background(bg_color)
            p.add_mesh(pmesh, scalars='rgb', rgb=True, lighting=False,
                       show_edges=False, show_scalar_bar=False)
            cx, cy = 0.5*(xmin+xmax), 0.5*(ymin+ymax)
            p.camera.position    = (cx, cy, 100.0)
            p.camera.focal_point = (cx, cy, 0.0)
            p.camera.up = (0.0, 1.0, 0.0)
            p.camera.parallel_projection = True
            p.camera.parallel_scale = 0.5 * (ymax - ymin)
            return p

        # ── Strategy: render twice (white bg + black bg) and recover true
        # alpha + un-tinted foreground colour from the difference. ──
        # The math:
        #   img_white = bg_white * (1-α) + fg * α      where bg_white = 1
        #   img_black = bg_black * (1-α) + fg * α      where bg_black = 0
        # So:
        #   img_white - img_black = bg_white * (1-α)   →   (1-α) = (img_white - img_black) / 1
        #   α = 1 - (img_white - img_black)
        #   fg * α = img_black                         →   fg = img_black / α   (where α > 0)
        #
        # This recovers a CORRECT alpha at every pixel including the anti-
        # aliased edge pixels, and a foreground colour with NO background
        # bleed. This is the standard "double-render" alpha extraction
        # used in compositing pipelines.
        p_w = _make_plotter('white')
        img_white = p_w.screenshot(return_img=True)
        p_w.close()
        p_b = _make_plotter('black')
        img_black = p_b.screenshot(return_img=True)
        p_b.close()

        # Per-pixel alpha: average across RGB channels for robustness
        diff = img_white.astype(np.int16) - img_black.astype(np.int16)  # 0..255
        # diff/255 = (1 - α). So α = 1 - diff/255, clamped to [0,1].
        alpha_per_channel = 1.0 - diff.astype(np.float32) / 255.0
        # Average channels — they should all give the same alpha for
        # opaque rendering, but matching/averaging is robust against
        # GPU rounding.
        alpha_pixel = np.clip(alpha_per_channel.mean(axis=2), 0.0, 1.0)

        # Foreground colour: img_black / α (where α > tiny)
        # For α=0 pixels (fully transparent), use 0.
        rgba = np.zeros((img_white.shape[0], img_white.shape[1], 4), dtype=np.uint8)
        opaque_enough = alpha_pixel > 0.01
        # Per-channel: fg_c = img_black_c / alpha
        for c in range(3):
            ch = img_black[:, :, c].astype(np.float32) / 255.0
            fg_c = np.zeros_like(ch)
            fg_c[opaque_enough] = ch[opaque_enough] / alpha_pixel[opaque_enough]
            rgba[:, :, c] = np.clip(fg_c * 255.0, 0, 255).astype(np.uint8)
        # Multiply per-pixel α by user-requested global alpha (default 0.85
        # for see-through profile)
        rgba[:, :, 3] = np.clip(alpha_pixel * alpha * 255.0, 0, 255).astype(np.uint8)
        return rgba
    except Exception:
        # Any failure (driver glitch, VTK regression, OOM) → caller
        # falls back to PolyCollection.
        return None


def _sample_face_colors_from_mesh(mesh, V):
    """Return an (F, 3) array of per-face RGB colours in [0, 1], or None.

    Tries three sources in order:
      1. Per-vertex colours  (mesh.visual.vertex_colors  — ColorVisuals)
      2. Texture image + UVs (mesh.visual.material.image — TextureVisuals)
      3. PBR baseColorTexture (mesh.visual.material.baseColorTexture)

    Photogrammetry meshes usually fall into case 2; legacy hand-painted
    meshes into case 1; glTF exports into case 3. The earlier code only
    handled case 1, so textured photogrammetry meshes silently rendered
    in greyscale even with the "Use mesh vertex colors" toggle on.
    """
    if not hasattr(mesh, "visual") or mesh.visual is None:
        return None

    F_arr = np.asarray(mesh.faces)

    # --- 1. per-vertex colours -------------------------------------------
    try:
        vc_attr = getattr(mesh.visual, "vertex_colors", None)
        if vc_attr is not None:
            vc = np.asarray(vc_attr)
            if len(vc) == len(V) and vc.shape[1] >= 3:
                vc_rgb = vc[:, :3].astype(float) / 255.0
                if vc_rgb.std() > 1e-3:
                    return vc_rgb[F_arr].mean(axis=1)
    except Exception:
        pass

    # --- 2 & 3. texture image + UVs --------------------------------------
    try:
        uv = getattr(mesh.visual, "uv", None)
        material = getattr(mesh.visual, "material", None)
        if uv is None or material is None:
            return None
        img = getattr(material, "image", None) or getattr(material, "baseColorTexture", None)
        if img is None:
            return None
        # PIL Image -> ndarray. Accept RGBA / RGB / L.
        img_arr = np.asarray(img.convert("RGB"))
        H, W = img_arr.shape[:2]
        # Skip placeholder textures (e.g. 2x2 white) left when the .jpg is missing.
        if H <= 4 and W <= 4:
            return None
        uv = np.asarray(uv, dtype=float)
        # OBJ/glTF: v origin at bottom; image rows count from top.
        u_pix = np.clip((uv[:, 0]      ) * (W - 1), 0, W - 1).astype(np.int32)
        v_pix = np.clip((1.0 - uv[:, 1]) * (H - 1), 0, H - 1).astype(np.int32)
        vert_rgb = img_arr[v_pix, u_pix].astype(float) / 255.0
        return vert_rgb[F_arr].mean(axis=1)
    except Exception:
        return None


# =============================================================================
# Data classes
# =============================================================================

@dataclass
class SherdResult:
    """Everything computed for one sherd."""
    name: str
    path: str
    unit: str = "cm"
    n_vertices: int = 0
    n_faces: int = 0
    bbox_extent: tuple = (0.0, 0.0, 0.0)
    axis_dir: tuple = (0.0, 1.0, 0.0)
    axis_point: tuple = (0.0, 0.0, 0.0)
    rim_diameter: float = 0.0
    max_diameter: float = 0.0
    shoulder_height: float = 0.0  # height below rim where max diameter occurs
    preserved_arc_deg: float = 0.0
    preserved_height: float = 0.0
    fit_residual_rmse: float = 0.0
    quality: str = "unknown"  # "good", "warn", "bad"
    section_azimuth_deg: float = 0.0
    form_class: str = "unknown"  # "plate", "bowl", "cup", "jar", "unknown"
    form_aspect_ratio: float = 0.0  # preserved_height / rim_radius
    rim_eversion_deg: float = 0.0  # angle of wall at rim from vertical (positive=outward)
    notes: list = field(default_factory=list)

    def to_flat_dict(self):
        d = asdict(self)
        d["axis_dir"] = ",".join(f"{v:.4f}" for v in self.axis_dir)
        d["axis_point"] = ",".join(f"{v:.3f}" for v in self.axis_point)
        d["bbox_extent"] = ",".join(f"{v:.2f}" for v in self.bbox_extent)
        d["notes"] = " | ".join(self.notes)
        return d


# =============================================================================
# Mesh loading and unit handling
# =============================================================================

def load_mesh(path):
    """Load a mesh as a trimesh.Trimesh. Falls back to process=True if needed."""
    m = trimesh.load(str(path), process=True, force="mesh")
    if not isinstance(m, trimesh.Trimesh):
        raise RuntimeError(f"{path} did not load as a single mesh")
    return m


def cluster_and_merge_components(mesh, distance_threshold=2.5):
    # Detect scale and rescale copy for clustering
    max_dim = max(mesh.extents)
    if max_dim < 5.0:
        scale = 100.0  # meters -> cm
    elif max_dim < 50.0:
        scale = 1.0    # cm -> cm
    else:
        scale = 0.1    # mm -> cm
        
    scaled_mesh = mesh.copy()
    if scale != 1.0:
        scaled_mesh.apply_scale(scale)
        
    components = scaled_mesh.split(only_watertight=False)
    # Filter out tiny components (less than 20 vertices)
    components = [c for c in components if len(c.vertices) >= 20]
    if not components:
        return [mesh]
        
    n = len(components)
    centroids = np.array([c.vertices.mean(axis=0) for c in components])
    
    # Proximity graph
    from scipy.spatial.distance import cdist
    adj = np.zeros((n, n), dtype=bool)
    for i in range(n):
        adj[i, i] = True
        for j in range(i+1, n):
            c_dist = np.linalg.norm(centroids[i] - centroids[j])
            # Only check detail if centroids are within 5x threshold
            if c_dist < distance_threshold * 5.0:
                vi = components[i].vertices
                vj = components[j].vertices
                if len(vi) > 150:
                    vi = vi[np.random.choice(len(vi), 150, replace=False)]
                if len(vj) > 150:
                    vj = vj[np.random.choice(len(vj), 150, replace=False)]
                min_d = cdist(vi, vj).min()
                if min_d < distance_threshold:
                    adj[i, j] = True
                    adj[j, i] = True
                    
    visited = np.zeros(n, dtype=bool)
    groups = []
    for i in range(n):
        if not visited[i]:
            group = []
            queue = [i]
            visited[i] = True
            while queue:
                curr = queue.pop(0)
                group.append(curr)
                for neighbor in range(n):
                    if adj[curr, neighbor] and not visited[neighbor]:
                        visited[neighbor] = True
                        queue.append(neighbor)
            groups.append(group)
            
    # Now, reconstruct the un-scaled meshes from groups of components
    original_components = mesh.split(only_watertight=False)
    original_components = [c for c in original_components if len(c.vertices) >= 20]
    
    merged_meshes = []
    for g in groups:
        # Merge components in this group
        group_comps = [original_components[idx] for idx in g]
        merged = trimesh.util.concatenate(group_comps)
        merged_meshes.append(merged)
        
    return merged_meshes


def load_meshes(path):
    """Load a mesh file. If it contains multiple objects (is a trimesh.Scene or has disconnected components),
    returns a list of (name, trimesh.Trimesh) tuples. Otherwise, returns a list of one tuple.
    """
    path = Path(path)
    # Load without forcing to a single mesh so we can preserve separate objects
    loaded = trimesh.load(str(path), process=True)
    
    import re
    def clean_name(name):
        return re.sub(r'[^a-zA-Z0-9_\-\.]', '_', name)
    
    meshes = []
    if isinstance(loaded, trimesh.Scene):
        # Extract all Trimesh geometries from the scene
        all_geoms = []
        for name, geom in loaded.geometry.items():
            if isinstance(geom, trimesh.Trimesh) and len(geom.vertices) > 0:
                all_geoms.append((name, geom))
        
        # Sort by number of faces descending (keep larger ones first)
        all_geoms.sort(key=lambda x: len(x[1].faces), reverse=True)
        
        unique_geoms = []
        for name, geom in all_geoms:
            is_duplicate = False
            for _, accepted_geom in unique_geoms:
                bounds_a = geom.bounds
                bounds_b = accepted_geom.bounds
                inter_min = np.maximum(bounds_a[0], bounds_b[0])
                inter_max = np.minimum(bounds_a[1], bounds_b[1])
                dims_a = bounds_a[1] - bounds_a[0]
                dims_b = bounds_b[1] - bounds_b[0]
                dims_inter = np.maximum(0.0, inter_max - inter_min)
                vol_a = np.prod(dims_a)
                vol_b = np.prod(dims_b)
                vol_inter = np.prod(dims_inter)
                min_vol = min(vol_a, vol_b)
                overlap = vol_inter / min_vol if min_vol > 1e-9 else 0.0
                if overlap > 0.8:
                    is_duplicate = True
                    break
            if not is_duplicate:
                unique_geoms.append((name, geom))
                
        for geom_name, geom_mesh in unique_geoms:
            cleaned_geom_name = clean_name(geom_name)
            try:
                components = cluster_and_merge_components(geom_mesh, distance_threshold=2.5)
            except Exception:
                components = [geom_mesh]
            if len(components) > 1:
                for idx, comp in enumerate(components):
                    if len(comp.vertices) > 0:
                        meshes.append((f"{path.stem}_{cleaned_geom_name}_{idx + 1}", comp))
            else:
                if len(geom_mesh.vertices) > 0:
                    meshes.append((f"{path.stem}_{cleaned_geom_name}", geom_mesh))
    elif isinstance(loaded, trimesh.Trimesh):
        try:
            components = cluster_and_merge_components(loaded, distance_threshold=2.5)
        except Exception:
            components = [loaded]
        if len(components) > 1:
            for idx, comp in enumerate(components):
                if len(comp.vertices) > 0:
                    meshes.append((f"{path.stem}_{idx + 1}", comp))
        else:
            if len(loaded.vertices) > 0:
                meshes.append((path.stem, loaded))
    else:
        raise RuntimeError(f"{path} loaded as unsupported type: {type(loaded)}")
        
    if not meshes:
        raise RuntimeError(f"No valid 3D meshes found in {path}")
    return meshes



def detect_units(extent_max_dim):
    """
    Heuristic: if the largest bbox dimension is in a plausible sherd size range
    we guess the units. Returns 'mm', 'cm', 'm', or 'unknown'.
    A typical sherd is 2-30 cm = 20-300 mm = 0.02-0.3 m.
    """
    if 0.02 < extent_max_dim < 0.5:
        # 0.02-0.5 m is plausible for a sherd in meters (common in Sketchfab/GLTF exports)
        return "m"
    elif 2 < extent_max_dim < 30:
        return "cm"
    elif 30 < extent_max_dim < 500:
        return "mm"
    else:
        return "unknown"


def scale_for_unit(unit_label):
    """Factor to multiply mesh coordinates by to get cm (sherdtool's internal unit)."""
    return {"mm": 0.1, "cm": 1.0, "m": 100.0}.get(unit_label, 1.0)


# =============================================================================
# Core geometry: axis fitting via multi-slice circle fits
# =============================================================================

def get_boundary_loop(mesh):
    """
    Return all boundary vertex indices (vertices on edges that belong to
    only one face). For meshes with multiple holes (common in 3D-scanned
    sherds), this returns vertices from ALL boundary loops, not just the
    first one. Used downstream for rim detection and broken-edge analysis.

    Implementation: pack each edge (a pair of vertex indices, already
    sorted by trimesh) into a single int64 by bit-shifting, then use a
    sort + run-length pass to find keys that appear exactly once. This
    is ~65× faster than the previous Counter(map(tuple, edges)) version
    on a 500k-face mesh (39 ms vs 2.5 s), which dominated render_plate's
    pre-clip time on every slider tick.
    """
    edges = np.asarray(mesh.edges_sorted, dtype=np.int64)
    if len(edges) == 0:
        return np.array([], dtype=int)
    # Pack two int32-range vertex indices into a single int64 hash. This
    # gives us a 1D key array we can sort cheaply.
    keys = (edges[:, 0] << 32) | edges[:, 1]
    keys.sort()
    # Find run boundaries: an edge appears `k` times if its key has k
    # consecutive equal entries in the sorted array.
    diff = np.empty(len(keys) + 1, dtype=bool)
    diff[0] = True; diff[-1] = True
    diff[1:-1] = keys[1:] != keys[:-1]
    starts = np.where(diff[:-1])[0]
    ends   = np.where(diff[1:])[0]
    counts = ends - starts + 1
    boundary_first = starts[counts == 1]
    if len(boundary_first) == 0:
        return np.array([], dtype=int)
    boundary_keys = keys[boundary_first]
    a = (boundary_keys >> 32).astype(np.int64)
    b = (boundary_keys & 0xFFFFFFFF).astype(np.int64)
    return np.unique(np.concatenate([a, b]))


def fit_circle_2d(pts2d):
    """Algebraic (Kasa) circle fit in 2D. Returns (xc, yc, R, residual_std)."""
    x, y = pts2d[:, 0], pts2d[:, 1]
    A = np.column_stack([2*x, 2*y, np.ones_like(x)])
    b = x*x + y*y
    sol, *_ = np.linalg.lstsq(A, b, rcond=None)
    xc, yc, c = sol
    R = float(np.sqrt(c + xc*xc + yc*yc))
    r_actual = np.sqrt((x-xc)**2 + (y-yc)**2)
    residual = float(np.std(r_actual - R))
    return float(xc), float(yc), R, residual


def fit_circle_chord_sagitta(pts2d):
    """Estimate circle from a partial arc using the chord+sagitta method.

    For an arc, the chord c (line connecting the two endpoints) and the
    sagitta s (max perpendicular distance from the chord to the arc) are
    related to the radius by:
        R = (c² + 4·s²) / (8·s)
    This is far more robust to noise than algebraic fits when the arc is
    less than 360°, because it depends only on three robust geometric
    measurements: the two arc-endpoints and the deepest point.

    Returns (xc, yc, R, residual_std). Falls back to NaN if degenerate.
    """
    if len(pts2d) < 3:
        return np.nan, np.nan, np.nan, np.nan

    # Find the two points farthest apart — these are the chord endpoints.
    # We use the convex hull approach for robustness: the diameter of the
    # convex hull is the maximum pairwise distance.
    pts = np.asarray(pts2d, dtype=float)
    # Pairwise distances on convex hull (small N, just brute-force)
    try:
        from scipy.spatial import ConvexHull
        hull = ConvexHull(pts)
        hull_pts = pts[hull.vertices]
    except Exception:
        # Fallback: use all points
        hull_pts = pts
    # Brute-force max distance among hull points
    n = len(hull_pts)
    max_d2 = 0.0; idx_a = 0; idx_b = 1
    for i in range(n):
        for j in range(i + 1, n):
            d2 = (hull_pts[i, 0] - hull_pts[j, 0])**2 + (hull_pts[i, 1] - hull_pts[j, 1])**2
            if d2 > max_d2:
                max_d2 = d2; idx_a = i; idx_b = j
    A = hull_pts[idx_a]; B = hull_pts[idx_b]
    chord_len = float(np.sqrt(max_d2))
    if chord_len < 1e-6:
        return np.nan, np.nan, np.nan, np.nan

    # Sagitta: perpendicular distance from chord (line A-B) to the most
    # distant point in the arc. Use signed perpendicular distance to
    # determine arc side; sagitta is the abs of the max in the dominant side.
    chord_dir = (B - A) / chord_len
    perp = np.array([-chord_dir[1], chord_dir[0]])
    rel = pts - A
    # Signed perpendicular distances of all points to the chord line
    signed_perp = rel @ perp
    # Sagitta = max abs signed_perp on the dominant side of the chord
    if np.median(signed_perp) >= 0:
        s = float(signed_perp.max())
    else:
        s = float(-signed_perp.min())
    if s < 1e-6:
        # Arc is essentially a straight line — radius is infinite
        return np.nan, np.nan, np.nan, np.nan

    # Radius from chord+sagitta:  R = (c² + 4s²) / (8s)
    R = (chord_len**2 + 4 * s * s) / (8.0 * s)
    if not np.isfinite(R) or R < 0.1:
        return np.nan, np.nan, np.nan, np.nan

    # Center: midpoint of chord, offset perpendicular by (R - s) toward
    # the side opposite the sagitta point. Sign: if median(signed_perp) ≥ 0
    # (arc is on +perp side), center is on -perp side.
    midpoint = 0.5 * (A + B)
    center_offset = R - s
    if np.median(signed_perp) >= 0:
        center = midpoint - center_offset * perp
    else:
        center = midpoint + center_offset * perp

    # Compute residual std of all points' distance from this center
    r_actual = np.sqrt((pts[:, 0] - center[0])**2 + (pts[:, 1] - center[1])**2)
    residual = float(np.std(r_actual - R))
    return float(center[0]), float(center[1]), float(R), residual


def fit_circle_hough(pts2d, R_min=0.5, R_max=50.0, n_R=80,
                      grid_resolution_factor=80):
    """Estimate circle by Hough voting in 2D.

    For each candidate radius R in [R_min, R_max] and each candidate center
    in a grid, count points lying within an annulus of radius R around the
    center. The (R, center) with the highest vote count wins.

    More robust than algebraic fits for partial arcs and noisy points because
    it doesn't try to minimize residuals — it finds the dominant circular
    pattern. Slower (O(N · n_R · grid²)) but acceptable for hundreds of
    rim points and a coarse 80×80 grid.

    Returns (xc, yc, R, residual_std). Returns NaN tuple if no clear peak.
    """
    if len(pts2d) < 3:
        return np.nan, np.nan, np.nan, np.nan

    pts = np.asarray(pts2d, dtype=float)
    x_min, x_max = pts[:, 0].min(), pts[:, 0].max()
    y_min, y_max = pts[:, 1].min(), pts[:, 1].max()
    pad = 0.5 * max(x_max - x_min, y_max - y_min)
    # Grid spans the bbox + padding (the center of a partial arc may lie
    # well outside the points themselves).
    cx_grid = np.linspace(x_min - pad, x_max + pad, grid_resolution_factor)
    cy_grid = np.linspace(y_min - pad, y_max + pad, grid_resolution_factor)
    R_grid = np.linspace(R_min, R_max, n_R)

    # Tolerance for "on the circle": small fraction of R, scales with R
    # so larger circles use proportionally more lenient tolerances.
    best_votes = 0; best = (np.nan, np.nan, np.nan, np.nan)
    for R in R_grid:
        tol = max(0.05, 0.02 * R)
        for cx in cx_grid:
            dx2 = (pts[:, 0] - cx)**2
            for cy in cy_grid:
                dy2 = (pts[:, 1] - cy)**2
                d = np.sqrt(dx2 + dy2)
                votes = int(np.sum(np.abs(d - R) < tol))
                if votes > best_votes:
                    best_votes = votes
                    # Compute residual on the inliers
                    inliers = np.abs(d - R) < tol
                    r_actual = d[inliers]
                    residual = float(np.std(r_actual - R)) if inliers.sum() > 1 else float("nan")
                    best = (float(cx), float(cy), float(R), residual)
    return best


def fit_circle_template_match(pts2d, D_min=2.0, D_max=50.0, D_step=0.5,
                                center_grid=50, tol=0.10, prior_R_hint=None,
                                return_all_peaks=False):
    """Vectorized rim-chart-style template matching.

    For each candidate diameter D, slide a circle of that diameter across a
    grid of center positions and count the rim points that lie within a
    tolerance ε of the circle. The (D, center) with the most votes wins.

    This is the computational analog of the manual rim-chart procedure
    (overlay a sherd's rim arc on a chart of concentric circles and slide
    until alignment). Unlike circle-fitting methods that AVERAGE all points
    (and thus collapse on the densest cluster — typically the inner curl
    of a rolled rim), template matching VOTES point-by-point and finds the
    diameter where the most rim points lie ON a single circle. For a sherd
    with both inner-curl points and outer-edge points, this produces TWO
    peaks in the D-vs-votes curve: a small-D peak from the curl and a
    large-D peak from the outer edge. We prefer the large-D peak (= the
    actual rim) unless a prior_R_hint pushes us elsewhere.

    Args:
        pts2d: (N, 2) rim points projected onto the rim plane.
        D_min, D_max, D_step: range and step for candidate diameters (cm).
        center_grid: grid resolution per axis for center search (e.g. 50).
        tol: distance tolerance ε for "point lies on the circle" (cm).
        prior_R_hint: if set, weights the score function toward this radius.
        return_all_peaks: if True, return list of all peaks for inspection.

    Returns:
        dict: {xc, yc, R, residual, method='template', votes, peaks?}
        or None if no useful match found.
    """
    if len(pts2d) < 5:
        return None

    pts = np.asarray(pts2d, dtype=float)
    x_min, x_max = pts[:, 0].min(), pts[:, 0].max()
    y_min, y_max = pts[:, 1].min(), pts[:, 1].max()
    bbox_size = max(x_max - x_min, y_max - y_min)
    # The center of a partial-arc rim circle can lie outside the bbox of
    # the points themselves (when the arc < 180°). Pad the search grid by
    # the maximum candidate radius so any plausible center is reachable.
    pad = D_max / 2.0
    cx_grid = np.linspace(x_min - pad, x_max + pad, center_grid)
    cy_grid = np.linspace(y_min - pad, y_max + pad, center_grid)

    # For each diameter D, the score is the maximum vote count over the
    # grid of candidate centers. We build a (D, score, best_cx, best_cy)
    # table.
    Ds = np.arange(D_min, D_max + D_step, D_step)
    score_curve = np.zeros(len(Ds))
    best_centers = np.zeros((len(Ds), 2))
    # Number of angular bins for coverage measurement (24 = 15° each)
    n_ang_bins = 24
    # Vectorize across centers for speed: for each D, compute distances
    # for all (center, point) pairs in one shot.
    for i, D in enumerate(Ds):
        R = D / 2.0
        # CX shape (gx, 1, 1), CY shape (1, gy, 1), pts shape (1, 1, n)
        dx = pts[None, None, :, 0] - cx_grid[:, None, None]
        dy = pts[None, None, :, 1] - cy_grid[None, :, None]
        d = np.sqrt(dx * dx + dy * dy)
        local_tol = max(tol, 0.015 * R)
        inlier_mask = np.abs(d - R) < local_tol  # shape (gx, gy, n)
        votes = np.sum(inlier_mask, axis=2)  # raw inlier count
        # ── ANGULAR COVERAGE WEIGHTING ──
        # A real rim circle has its inliers spread around the
        # circumference; a spurious tangent-to-large-circle has them
        # clumped in one narrow sector. We measure coverage by binning
        # inlier angles into 24 sectors (15° each) and counting how many
        # sectors have at least one inlier. This handles wrap-around at
        # ±π automatically since the bins are circular.
        ang = np.arctan2(dy, dx)  # shape (gx, gy, n) in [-π, π]
        # Bin index: 0..n_ang_bins-1
        bin_idx = ((ang + np.pi) / (2 * np.pi) * n_ang_bins).astype(int)
        bin_idx = np.clip(bin_idx, 0, n_ang_bins - 1)
        # For each (gx, gy), count distinct bins that contain an inlier.
        # Trick: build a (gx, gy, n_ang_bins) one-hot tensor, OR-reduce
        # over n inliers, count True bins.
        gx_n, gy_n = inlier_mask.shape[0], inlier_mask.shape[1]
        # Suppressed bin index where not inlier (use -1 sentinel)
        masked_bin = np.where(inlier_mask, bin_idx, -1)
        # Count distinct bins per (gx, gy). Vectorized via one-hot:
        # build (gx, gy, n_ang_bins) bool, set true where any point
        # falls in that bin AND is an inlier.
        coverage = np.zeros((gx_n, gy_n), dtype=np.float32)
        for b in range(n_ang_bins):
            has_b = ((masked_bin == b)).any(axis=2)
            coverage += has_b.astype(np.float32)
        coverage_factor = coverage / float(n_ang_bins)  # in [0, 1]
        # Weighted score: inliers × angular coverage. A circle with 100
        # inliers spread over 18 of 24 bins (75% coverage) scores 75;
        # a circle with 100 inliers all in 2 bins (8% coverage) scores 8.
        weighted = votes * coverage_factor
        flat_idx = int(np.argmax(weighted))
        gx, gy = np.unravel_index(flat_idx, weighted.shape)
        score_curve[i] = float(weighted[gx, gy])
        best_centers[i] = (cx_grid[gx], cy_grid[gy])

    if score_curve.max() < 5:
        # No diameter gathered a meaningful vote count — bail out.
        return None

    # ── Peak detection ──
    # Find local maxima in the score curve. A local max requires score[i]
    # to be ≥ neighbors. We then keep peaks reaching at least 50% of the
    # global max.
    peaks = []
    threshold = 0.5 * score_curve.max()
    for i in range(len(Ds)):
        is_local_max = True
        # Check small neighborhood (±2 indices)
        lo = max(0, i - 2); hi = min(len(Ds), i + 3)
        if score_curve[i] != score_curve[lo:hi].max():
            is_local_max = False
        if is_local_max and score_curve[i] >= threshold:
            peaks.append({
                "D": float(Ds[i]),
                "R": float(Ds[i] / 2.0),
                "votes": float(score_curve[i]),
                "xc": float(best_centers[i, 0]),
                "yc": float(best_centers[i, 1]),
            })

    if not peaks:
        # Fallback: just pick the global argmax even if no clear "peak"
        i = int(np.argmax(score_curve))
        peaks = [{
            "D": float(Ds[i]),
            "R": float(Ds[i] / 2.0),
            "votes": float(score_curve[i]),
            "xc": float(best_centers[i, 0]),
            "yc": float(best_centers[i, 1]),
        }]

    # ── Peak selection ──
    # Strategy 1: if prior_R_hint is supplied, pick peak closest to it
    # within a 50% tolerance.
    if prior_R_hint is not None and prior_R_hint > 0:
        within_prior = [p for p in peaks
                        if abs(p["R"] - prior_R_hint) / prior_R_hint < 0.50]
        if within_prior:
            chosen = max(within_prior, key=lambda p: p["votes"])
        else:
            chosen = max(peaks, key=lambda p: p["votes"])
    else:
        # Strategy 2: archaeological prior — prefer the LARGER diameter
        # (outer rim edge) when peaks are close in score. Specifically, if
        # the largest-D peak has at least 75% of the max-vote count, pick
        # the largest D. Otherwise pick the highest-vote peak.
        max_votes = max(p["votes"] for p in peaks)
        candidates_close_to_max = [p for p in peaks
                                    if p["votes"] >= 0.75 * max_votes]
        chosen = max(candidates_close_to_max, key=lambda p: p["D"])

    # Compute residual std for all rim points relative to chosen circle
    cx_c = chosen["xc"]; cy_c = chosen["yc"]; R_c = chosen["R"]
    dist = np.sqrt((pts[:, 0] - cx_c)**2 + (pts[:, 1] - cy_c)**2)
    inliers = np.abs(dist - R_c) < tol * 2  # generous for residual calc
    if inliers.sum() > 1:
        residual = float(np.std(dist[inliers] - R_c))
    else:
        residual = float("nan")

    result = {
        "xc": cx_c, "yc": cy_c, "R": R_c,
        "residual": residual,
        "method": "template",
        "votes": chosen["votes"],
    }
    if return_all_peaks:
        result["peaks"] = peaks
        result["score_curve"] = (Ds.tolist(), score_curve.tolist())
    return result


def fit_rim_circle_robust(pts2d, prior_R_hint=None):
    """Combine algebraic + chord-sagitta estimators with cross-validation.

    Two-method ensemble: Kasa algebraic fit (fast, accurate for clean
    nearly-full circles) and chord-sagitta (robust for partial arcs).
    Template matching exists as a separate function (`fit_circle_template_match`)
    but is not in the default ensemble — it has practical precision issues
    on multi-radius rim point clouds and is provided as opt-in for future
    experimentation.

    Selection rules:
      • Filter candidates to within ±50% of prior_R_hint if supplied.
      • If methods agree within 15%, average them.
      • Otherwise prefer chord-sagitta (more reliable for partial arcs).
      • If prior_R_hint exists, prefer candidate closest to it.
    """
    candidates = []

    # 1. Algebraic (Kasa) fit
    try:
        xc, yc, R, res = fit_circle_2d(pts2d)
        if np.isfinite(R) and 0.5 < R < 50:
            candidates.append({
                "xc": xc, "yc": yc, "R": R, "residual": res,
                "method": "algebraic",
            })
    except Exception:
        pass

    # 2. Chord+sagitta fit
    try:
        xc, yc, R, res = fit_circle_chord_sagitta(pts2d)
        if np.isfinite(R) and 0.5 < R < 50:
            candidates.append({
                "xc": xc, "yc": yc, "R": R, "residual": res,
                "method": "sagitta",
            })
    except Exception:
        pass

    if not candidates:
        return None

    # Filter by prior if supplied: reject candidates more than 50% off
    if prior_R_hint is not None and prior_R_hint > 0:
        plausible = [c for c in candidates
                     if abs(c["R"] - prior_R_hint) / prior_R_hint < 0.50]
        if plausible:
            candidates = plausible

    # If methods agree (within 15% spread), average them
    if len(candidates) == 2:
        R1, R2 = candidates[0]["R"], candidates[1]["R"]
        if abs(R1 - R2) / max(R1, R2) < 0.15:
            avg_R = 0.5 * (R1 + R2)
            sagitta = next((c for c in candidates if c["method"] == "sagitta"),
                            candidates[0])
            return {
                "xc": sagitta["xc"], "yc": sagitta["yc"], "R": avg_R,
                "residual": min(c["residual"] for c in candidates),
                "method": "agree",
            }

    # If prior hint, pick closest
    if prior_R_hint is not None and prior_R_hint > 0:
        return min(candidates, key=lambda c: abs(c["R"] - prior_R_hint))

    # Default: prefer chord-sagitta for partial arcs
    sagitta = next((c for c in candidates if c["method"] == "sagitta"), None)
    return sagitta if sagitta is not None else candidates[0]



def _one_pass_fit(mesh, yhat, p0_guess, n_slices=30):
    """One pass of multi-slice circle fits with a given axis direction + point."""
    V = np.asarray(mesh.vertices)
    Nv = np.asarray(mesh.vertex_normals)
    yhat = yhat / np.linalg.norm(yhat)

    tmp = np.array([1.0, 0, 0])
    if abs(tmp @ yhat) > 0.9:
        tmp = np.array([0, 0, 1.0])
    e_a = np.cross(tmp, yhat); e_a /= np.linalg.norm(e_a)
    e_b = np.cross(yhat, e_a); e_b /= np.linalg.norm(e_b)

    delta = V - p0_guess
    H = delta @ yhat
    radial_vec = delta - np.outer(H, yhat)
    r_pre = np.linalg.norm(radial_vec, axis=1)
    rhat = radial_vec / np.maximum(r_pre[:, None], 1e-6)
    radial_comp = (Nv * rhat).sum(axis=1)
    exterior = radial_comp > 0.4
    if exterior.sum() < 500:
        exterior = radial_comp > 0.2
    if exterior.sum() < 200:
        return None

    coord_a = delta @ e_a
    coord_b = delta @ e_b
    H_min, H_max = H[exterior].min(), H[exterior].max()
    h_bins = np.linspace(H_min + 1, H_max - 1, n_slices)
    slice_hw = (H_max - H_min) / n_slices * 0.7

    records = []
    for hc in h_bins:
        sel = exterior & (np.abs(H - hc) < slice_hw)
        if sel.sum() < 40: continue
        xc, yc, R, resid = fit_circle_2d(np.column_stack([coord_a[sel], coord_b[sel]]))
        # reject grossly bad fits
        if R > 0.5 * max(mesh.extents) * 3:  # sanity cap
            continue
        records.append((hc, xc, yc, R, resid, int(sel.sum())))

    if len(records) < 4:
        return None

    rec = np.array(records)
    return {
        "yhat": yhat, "p0": p0_guess.copy(),
        "e_a": e_a, "e_b": e_b,
        "records": rec, "exterior": exterior,
        "H": H, "h_bins": h_bins, "slice_hw": slice_hw,
    }


def multi_slice_axis_fit(mesh, init_axis_dir, n_slices=30, n_iter=3, verbose=False,
                          max_axis_deviation_deg=None):
    """
    Iteratively fit axis + per-slice circles:
      - Slice perpendicular to current axis estimate, fit circles in each slice
      - Circle centers from different heights should lie on a common line (the axis)
      - Fit a 3D line through the circle centers → refined axis direction + point
      - Iterate, but only accept updates that decrease RMSE

    This refines BOTH axis direction and axis location. Important when the scanner
    orientation doesn't align with the vessel's natural axis.

    For shallow rim sherds (bowls/plates with small preserved arc), the iterative
    refinement can drift to a degenerate axis that fits the partial arc better
    but tilts the vessel inappropriately. Pass max_axis_deviation_deg to clamp
    how far the refined axis can drift from the initial guess.
    """
    V = np.asarray(mesh.vertices)
    yhat = np.asarray(init_axis_dir, dtype=float)
    yhat /= np.linalg.norm(yhat)
    init_yhat = yhat.copy()  # remember the user's starting hint
    p0 = V.mean(axis=0)

    best_yhat = yhat.copy()
    best_p0 = p0.copy()
    best_rmse = np.inf
    best_info = None

    for it in range(n_iter):
        pass_info = _one_pass_fit(mesh, yhat, p0, n_slices=n_slices)
        if pass_info is None:
            if best_info is None: return None
            break
        rec = pass_info["records"]
        e_a, e_b = pass_info["e_a"], pass_info["e_b"]

        # Keep only good slices (tight filter for axis update)
        med_resid = np.median(rec[:, 4])
        good = rec[(rec[:, 4] < max(2.0, 2 * med_resid)) & (rec[:, 5] > 80)]
        if len(good) < 5:
            good = rec[(rec[:, 4] < max(5.0, 3 * med_resid)) & (rec[:, 5] > 60)]
        if len(good) < 3:
            good = rec

        rmse_now = float(np.sqrt(np.mean(good[:, 4] ** 2)))
        if verbose:
            print(f"   iter {it}: axis {yhat.round(3)}, "
                  f"RMSE {rmse_now:.2f}, good slices {len(good)}/{len(rec)}")

        # Save best
        if rmse_now < best_rmse:
            best_rmse = rmse_now
            best_yhat = yhat.copy()
            best_p0 = p0.copy()
            best_info = (pass_info, good)

        if it == n_iter - 1: break  # last iter

        # Propose updated axis via PCA of circle centers (only using very good slices)
        very_good = good[good[:, 4] < np.percentile(good[:, 4], 75)]
        if len(very_good) < 5: very_good = good
        centers_3d = (np.outer(very_good[:, 0], yhat) +
                      np.outer(very_good[:, 1], e_a) +
                      np.outer(very_good[:, 2], e_b) + p0)
        c_mean = centers_3d.mean(axis=0)
        _, _, vt = np.linalg.svd(centers_3d - c_mean, full_matrices=False)
        new_yhat = vt[0]
        if new_yhat @ yhat < 0: new_yhat = -new_yhat

        # Damp the update: move only part of the way
        damping = 0.5
        yhat_proposed = yhat + damping * (new_yhat - yhat)
        yhat_proposed /= np.linalg.norm(yhat_proposed)

        # Constrain the axis to stay within max_axis_deviation_deg of the
        # initial axis. This is critical for shallow rim sherds where the
        # circle fit is poorly conditioned and can drift to degenerate
        # tilted solutions.
        if max_axis_deviation_deg is not None:
            cos_dev = float(np.clip(yhat_proposed @ init_yhat, -1, 1))
            dev_deg = float(np.degrees(np.arccos(abs(cos_dev))))
            if dev_deg > max_axis_deviation_deg:
                if verbose:
                    print(f"     proposed axis deviates {dev_deg:.1f}° from "
                          f"initial (>{max_axis_deviation_deg}° limit), clamping")
                # Project yhat_proposed back onto the cone of allowed deviations
                # by rotating it back toward init_yhat
                # Use slerp-like interpolation
                cos_max = np.cos(np.radians(max_axis_deviation_deg))
                if cos_dev < 0: yhat_proposed = -yhat_proposed
                # Linear interp + renormalize is good enough for small angles
                t = max_axis_deviation_deg / max(dev_deg, 1e-6)
                yhat_proposed = init_yhat + t * (yhat_proposed - init_yhat)
                yhat_proposed /= np.linalg.norm(yhat_proposed)

        # Test the proposed axis
        test = _one_pass_fit(mesh, yhat_proposed, c_mean, n_slices=n_slices)
        if test is None:
            if verbose: print(f"     proposed axis failed — stopping")
            break
        test_rec = test["records"]
        test_good = test_rec[(test_rec[:, 4] < max(5.0, 3*np.median(test_rec[:, 4]))) &
                              (test_rec[:, 5] > 60)]
        if len(test_good) < 3: test_good = test_rec
        test_rmse = float(np.sqrt(np.mean(test_good[:, 4] ** 2)))

        if test_rmse < rmse_now - 0.05:  # only accept if clear improvement
            yhat = yhat_proposed
            p0 = c_mean
        else:
            if verbose: print(f"     proposed axis doesn't improve RMSE ({test_rmse:.2f}), stopping")
            break

    if best_info is None: return None
    pass_info, good = best_info
    yhat, p0 = best_yhat, best_p0
    e_a, e_b = pass_info["e_a"], pass_info["e_b"]
    exterior = pass_info["exterior"]
    h_bins = pass_info["h_bins"]; slice_hw = pass_info["slice_hw"]

    # Compute final r(h) profile with the best axis
    V_rec = V - p0
    H_new = V_rec @ yhat
    radial_new = V_rec - np.outer(H_new, yhat)
    r_new = np.linalg.norm(radial_new, axis=1)
    profile = []
    for hc in h_bins:
        sel = exterior & (np.abs(H_new - hc) < slice_hw)
        if sel.sum() < 30: continue
        profile.append((float(hc), float(np.percentile(r_new[sel], 90))))
    profile = np.array(profile) if profile else None
    rmse = best_rmse

    if verbose:
        print(f"   final axis: {yhat.round(3)}, RMSE {rmse:.2f}, {len(good)} good slices")

    return {
        "axis_dir": yhat,
        "axis_point": p0,
        "e_a": e_a, "e_b": e_b,
        "profile_h": profile[:, 0] if profile is not None else None,
        "profile_r": profile[:, 1] if profile is not None else None,
        "rmse": rmse,
        "n_good_slices": len(good),
        "n_total_slices": len(pass_info["records"]),
    }



def _find_rim_arc(mesh, V, Nv, verbose=False):
    """
    Walk the mesh boundary as ordered chains, then find the best rim arc by
    sliding a window over each chain and looking for the longest contiguous
    segment that fits a circle very well, AND whose plane normal aligns with
    the vessel's principal axis (longest PCA dimension).

    A real rim has:
      - Smooth boundary (consecutive points close together)
      - Lies on a circle (low circle fit residual)
      - Lies on a plane (low plane fit residual)
      - Plane normal roughly aligned with vessel's long axis (since the rim
        is perpendicular to the axis)
      - Reasonably long arc (≥ ~1/4 of expected rim circumference)
    """
    edges_sorted = mesh.edges_sorted
    from collections import Counter, defaultdict
    edge_keys = [tuple(e) for e in edges_sorted]
    counts = Counter(edge_keys)
    boundary_edges = [tuple(e) for e in edges_sorted if counts[tuple(e)] == 1]
    if len(boundary_edges) < 5:
        return None

    adj = defaultdict(list)
    for a, b in boundary_edges:
        adj[a].append(b); adj[b].append(a)
    visited_edges = set()
    chains = []
    boundary_verts = list(adj.keys())

    def walk_from(start, prev=None):
        chain = [start]
        cur = start
        while True:
            neighbors = [n for n in adj[cur] if n != prev]
            if not neighbors: break
            nxt = neighbors[0]
            edge_key = (min(cur, nxt), max(cur, nxt))
            if edge_key in visited_edges: break
            visited_edges.add(edge_key)
            chain.append(nxt)
            prev = cur; cur = nxt
            if cur == start: break
        return chain

    for v in boundary_verts:
        if len(adj[v]) == 1:
            ch = walk_from(v)
            if len(ch) > 5: chains.append(ch)
    for v in boundary_verts:
        if any((min(v,n), max(v,n)) not in visited_edges for n in adj[v]):
            ch = walk_from(v)
            if len(ch) > 5: chains.append(ch)

    if verbose:
        print(f"   Found {len(chains)} boundary chains, "
              f"sizes: {sorted([len(c) for c in chains], reverse=True)[:10]}")

    if not chains:
        return None

    # Compute the mesh's principal axis (longest PCA direction). The rim plane
    # normal should be roughly aligned with this axis for a typical rim sherd.
    V_c = V - V.mean(axis=0)
    try:
        _, S, Vt = np.linalg.svd(V_c, full_matrices=False)
        # Note: Vt rows are the principal axes. Vt[0] is longest, Vt[2] is shortest.
        principal_axis = Vt[0] / np.linalg.norm(Vt[0])
    except np.linalg.LinAlgError:
        principal_axis = np.array([0., 1., 0.])

    if verbose:
        print(f"   Mesh PCA: SV={S.round(2)}, principal axis={principal_axis.round(2)}")

    def fit_arc(pts):
        if len(pts) < 8: return None
        cen = pts.mean(axis=0)
        pts_c = pts - cen
        try:
            _, S, Vt = np.linalg.svd(pts_c, full_matrices=False)
        except np.linalg.LinAlgError:
            return None
        normal = Vt[2]
        e_a = Vt[0]; e_b = Vt[1]
        pts_2d = np.column_stack([pts_c @ e_a, pts_c @ e_b])
        try:
            xc, yc, r_c, c_rmse = fit_circle_2d(pts_2d)
        except Exception:
            return None
        if not np.isfinite(r_c) or r_c < 0.1: return None
        plane_resid = np.abs(pts_c @ normal)
        p_rmse = float(np.sqrt(np.mean(plane_resid**2)))
        return {
            "centroid": cen, "normal": normal, "e_a": e_a, "e_b": e_b,
            "xc": xc, "yc": yc, "r_circ": r_c,
            "plane_rmse": p_rmse, "circle_rmse": c_rmse,
        }

    best_arc = None; best_quality = np.inf

    for chain in chains:
        if len(chain) < 12: continue
        chain_pts = V[chain]

        n = len(chain)
        window_sizes = sorted(set([max(12, int(n*frac)) for frac in
                                    [1.0, 0.85, 0.7, 0.55, 0.4, 0.25]]))

        for w in window_sizes:
            if w > n: continue
            stride = max(1, w // 4)
            for start in range(0, n - w + 1, stride):
                end = start + w
                pts = chain_pts[start:end]
                fit = fit_arc(pts)
                if fit is None: continue
                rel_circ = fit["circle_rmse"] / max(fit["r_circ"], 0.1)
                rel_plane = fit["plane_rmse"] / max(fit["r_circ"], 0.1)

                # Plane-normal alignment with PCA axis: |normal · principal_axis|
                # Should be HIGH (close to 1) for a true rim
                alignment = abs(fit["normal"] @ principal_axis)

                # Score: lower = better
                # Strongly penalize misalignment with PCA (1 - alignment)
                length_bonus = -np.log(max(w / 30.0, 0.5))
                misalignment_penalty = (1.0 - alignment) * 5.0
                quality = (rel_circ * 8 + rel_plane * 3 +
                           misalignment_penalty + length_bonus)

                if quality < best_quality:
                    best_quality = quality
                    best_arc = {**fit,
                                 "chain": chain[start:end],
                                 "pts": pts,
                                 "rel_circ": rel_circ,
                                 "rel_plane": rel_plane,
                                 "alignment": alignment,
                                 "n_pts": w}

    if best_arc is None:
        return None
    if verbose:
        print(f"   Best arc: n={best_arc['n_pts']}, r={best_arc['r_circ']:.2f}, "
              f"rel_circ={best_arc['rel_circ']:.3f}, "
              f"rel_plane={best_arc['rel_plane']:.3f}, "
              f"PCA-alignment={best_arc['alignment']:.3f}")
    return best_arc


def fit_rim_plane(mesh, verbose=False, axis_hint=None):
    """
    Scientifically determine the vessel axis from rim geometry.

    Method:
      1. Build the boundary edge chains of the mesh.
      2. For each connected chain, fit a plane and a circle on that plane.
      3. Pick the chain with the best circle fit (low relative residual)
         while preferring longer chains. This is the rim.
      4. Refine via outlier rejection and re-fit.
      5. The plane normal is the vessel axis. Orient it so it points from
         the body up toward the rim.

    Works for any orientation in space, doesn't require an initial axis hint.
    """
    V = np.asarray(mesh.vertices)
    Nv = np.asarray(mesh.vertex_normals)

    best = _find_rim_arc(mesh, V, Nv, verbose=verbose)
    if best is None:
        if verbose: print("   No rim arc found.")
        return None

    rim_pts = best["pts"]
    centroid = best["centroid"]
    normal = best["normal"]
    e_a = best["e_a"]; e_b = best["e_b"]
    xc, yc, r_c = best["xc"], best["yc"], best["r_circ"]
    plane_rmse = best["plane_rmse"]; circle_rmse = best["circle_rmse"]

    # Refine: drop outliers
    pts_c = rim_pts - centroid
    pts_2d = np.column_stack([pts_c @ e_a, pts_c @ e_b])
    rad_resid = np.abs(np.linalg.norm(pts_2d - np.array([xc, yc]), axis=1) - r_c)
    plane_resid = np.abs(pts_c @ normal)
    keep = (rad_resid < max(0.15 * r_c, 0.2)) & (plane_resid < max(0.15 * r_c, 0.15))
    if keep.sum() >= 8:
        rim_pts = rim_pts[keep]
        centroid = rim_pts.mean(axis=0)
        pts_c = rim_pts - centroid
        try:
            _, S, Vt = np.linalg.svd(pts_c, full_matrices=False)
            normal = Vt[2]
            e_a = Vt[0]; e_b = Vt[1]
            pts_2d = np.column_stack([pts_c @ e_a, pts_c @ e_b])
            xc, yc, r_c, circle_rmse = fit_circle_2d(pts_2d)
            plane_rmse = float(np.sqrt(np.mean((pts_c @ normal)**2)))
            if verbose:
                print(f"   After outlier removal ({len(rim_pts)} kept): "
                      f"r={r_c:.3f}, plane_rmse={plane_rmse:.3f}, "
                      f"circle_rmse={circle_rmse:.3f}")
        except Exception:
            pass

    # Orient axis so it points from body toward the rim
    rim_center_3d = centroid + xc * e_a + yc * e_b
    body_centroid = V.mean(axis=0)
    if (rim_center_3d - body_centroid) @ normal < 0:
        normal = -normal

    # Quality
    rel_circle_err = circle_rmse / max(r_c, 0.01)
    rel_plane_err = plane_rmse / max(r_c, 0.01)
    if rel_circle_err < 0.04 and rel_plane_err < 0.04:
        quality = "good"
    elif rel_circle_err < 0.10 and rel_plane_err < 0.10:
        quality = "fair"
    else:
        quality = "poor"

    inclination_deg = float(np.degrees(np.arccos(
        abs(np.clip(normal @ np.array([0, 1, 0]), -1, 1)))))

    return {
        "axis_dir": normal,
        "rim_center": rim_center_3d,
        "rim_radius": float(r_c),
        "rim_points": rim_pts,
        "plane_residual_rmse": plane_rmse,
        "circle_residual_rmse": circle_rmse,
        "inclination_deg": inclination_deg,
        "quality": quality,
        "n_rim_points": len(rim_pts),
    }


def auto_orient_from_rim(mesh, verbose=False):
    """
    Scientifically determine the vessel axis and inclination from a 3D rim
    sherd. Returns a rotation matrix that aligns the mesh so its axis is +Y
    (rim up).

    Algorithm:
      1. Run multi-slice axis fit from many starting directions (PCA axes +
         cardinal axes), picking the one that gives the lowest residuals.
      2. Refine using rim-plane fit: find boundary points near the rim end
         of the axis projection, fit a plane to them, and use that plane's
         normal as the refined axis.
      3. Validate by checking the rim points actually form a circle.

    Returns dict:
      - R: 3x3 rotation matrix aligning the axis to +Y
      - axis_orig: detected axis in original coordinates
      - rim_info: detailed rim fit info (radius, residuals, quality, ...)
      - inclination_deg: angle between detected axis and +Y in original coords
    """
    V = np.asarray(mesh.vertices)
    Nv = np.asarray(mesh.vertex_normals)

    # Step 1: try many starting axes
    candidate_axes = [
        np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0]),
    ]
    # Add PCA axes
    try:
        V_c = V - V.mean(axis=0)
        _, S, Vt = np.linalg.svd(V_c, full_matrices=False)
        for i in range(3):
            candidate_axes.append(Vt[i])
    except np.linalg.LinAlgError:
        pass

    # Get boundary points up front (used by early-out and candidate scoring)
    try:
        loop = get_boundary_loop(mesh)
        Bp = V[loop] if len(loop) > 5 else None
    except Exception:
        loop = np.array([], dtype=int)
        Bp = None

    # Early-out check: is the mesh ALREADY correctly oriented (rim up along +Y)?
    # Strategy: check if the cardinal +Y axis gives a good multi-slice fit
    # AND no other candidate axis gives a SIGNIFICANTLY better fit. This is
    # robust even for messy non-watertight meshes where boundary detection
    # is unreliable.
    try:
        quick_fit = multi_slice_axis_fit(mesh, np.array([0., 1., 0.]),
                                          verbose=False)
    except Exception:
        quick_fit = None
    if (quick_fit is not None and quick_fit.get("profile_r") is not None
            and len(quick_fit["profile_r"]) >= 3
            and abs(quick_fit["axis_dir"][1]) > 0.95):
        # Compare cardinal Y fit to the best alternative
        y_rmse = float(quick_fit["rmse"])
        y_rrange = float(quick_fit["profile_r"].max() - quick_fit["profile_r"].min())
        y_score = y_rrange / max(0.5, y_rmse)

        # Also check: is the mesh's Y range positive at the top?
        # i.e., is the centroid below the rim? If the centroid is above
        # half-height (rim should be at top), this is suspicious.
        V_y = V[:, 1]
        y_min = V_y.min(); y_max = V_y.max(); y_rng = y_max - y_min
        # The rim is "at top" if the wider, more curvy part is at the top.
        # Use vertex distribution: upper third should have at least as many
        # vertices as the lower third (a vessel narrows toward the base/break).
        upper_frac = (V_y > y_max - 0.30 * y_rng).sum() / len(V_y)
        lower_frac = (V_y < y_min + 0.30 * y_rng).sum() / len(V_y)
        # Strict check: rim at top means upper > lower (more vertices near rim
        # than near the broken base, since the rim circumference is wider)
        rim_likely_up = upper_frac > lower_frac

        # Quick test: rotate around X by 90° and see if the Y-fit is markedly
        # better in that orientation. If so, the mesh is misoriented.
        try:
            R_x90 = np.array([[1,0,0], [0,0,-1], [0,1,0]], dtype=float)
            mesh_test = mesh.copy()
            mesh_test.apply_transform(np.block([[R_x90, np.zeros((3,1))],
                                                  [np.zeros((1,3)), [[1.0]]]]))
            test_fit = multi_slice_axis_fit(mesh_test, np.array([0.,1.,0.]),
                                              verbose=False)
            if test_fit is not None and test_fit.get("profile_r") is not None \
                    and len(test_fit["profile_r"]) >= 3:
                test_rmse = float(test_fit["rmse"])
                test_rrange = float(test_fit["profile_r"].max()
                                     - test_fit["profile_r"].min())
                test_score = test_rrange / max(0.5, test_rmse)
            else:
                test_score = 0.0
        except Exception:
            test_score = 0.0

        if verbose:
            print(f"   Cardinal Y-axis fit: rmse={y_rmse:.3f}, "
                  f"r_range={y_rrange:.2f}, score={y_score:.2f}")
            print(f"   Y-rotated 90°X test: score={test_score:.2f}")
            print(f"   Rim-likely-up: upper={upper_frac:.2%}, "
                  f"lower={lower_frac:.2%}, ok={rim_likely_up}")

        # Trigger early-out if:
        # - Cardinal Y gives a decent fit (score > 4)
        # - The Y-axis is close to the multi-slice fit's converged axis (>0.95)
        # - The rotated alternative isn't dramatically better (within 50%)
        # - The mesh isn't visibly upside-down
        if (y_score > 4 and y_score > test_score * 0.7 and rim_likely_up):
            if verbose:
                print(f"   Mesh is already correctly oriented along Y. "
                      f"No rotation needed.")
            axis = quick_fit["axis_dir"]
            R_id = np.eye(3)
            inc_deg = float(np.degrees(np.arccos(
                abs(np.clip(axis @ np.array([0, 1, 0]), -1, 1)))))
            # Fallback rim radius from profile
            rim_radius = float(quick_fit["profile_r"].max())
            rim_info = {
                "axis_dir": axis,
                "rim_radius": rim_radius,
                "rim_plane_rmse": float("nan"),
                "rim_circle_rmse": y_rmse,
                "multi_slice_rmse": y_rmse,
                "n_rim_points": 0,
                "rim_points": None,
                "quality": "good",
                "inclination_deg": inc_deg,
                "plane_residual_rmse": float("nan"),
                "circle_residual_rmse": y_rmse,
                "already_oriented": True,
            }
            return {
                "R": R_id,
                "axis_orig": axis,
                "inclination_deg": inc_deg,
                "rim_info": rim_info,
                "fit": quick_fit,
                "already_oriented": True,
            }

    # Run multi_slice_axis_fit on each candidate
    extents = mesh.extents
    short_axis_idx = int(np.argmin(extents))
    best_fit = None; best_score = -np.inf

    if verbose:
        print(f"   Mesh PCA SVs: {S.round(2) if 'S' in locals() else 'N/A'}")
        print(f"   Trying {len(candidate_axes)} candidate axes...")

    for ci, c in enumerate(candidate_axes):
        c_norm = c / np.linalg.norm(c)
        try:
            fit = multi_slice_axis_fit(mesh, c_norm, verbose=False)
        except Exception as e:
            if verbose: print(f"   axis #{ci+1} {c_norm.round(2)}: FAILED ({e})")
            continue
        if fit is None or fit["profile_r"] is None or len(fit["profile_r"]) < 3:
            continue
        rmse = fit["rmse"]
        r_range = float(fit["profile_r"].max() - fit["profile_r"].min())
        # Penalize axes aligned with shortest mesh dimension (sherds laid flat)
        c_abs = np.abs(c_norm)
        flat_penalty = 1.0 if c_abs[short_axis_idx] > 0.85 else 0.0

        # Rim-concentration bonus: project boundary onto the FITTED axis
        # (not the candidate, which may have been refined). For a true vessel
        # axis, the rim is at one extreme of the projection. We check whether
        # the boundary points have a "circular cap" of higher r at one end —
        # i.e., the points in the top 15% of axis projection have larger
        # average r than points in the bottom 85%, OR vice versa. A real rim
        # axis ALSO has the property that the rim is strongly clustered at
        # one extreme — the top 8% of axis projection holds ≥15% of all
        # boundary points (because the rim is a high-density circular arc).
        rim_concentration_bonus = 0.0
        rim_extreme_bonus = 0.0
        if Bp is not None and fit.get("axis_dir") is not None:
            ax_d = fit["axis_dir"]
            ax_p = fit["axis_point"]
            Bp_h = (Bp - ax_p) @ ax_d
            h_max = Bp_h.max(); h_min = Bp_h.min(); h_rng = h_max - h_min
            if h_rng > 0.5:
                # Check density at top 8%
                top_density = (Bp_h > h_max - 0.08 * h_rng).sum() / len(Bp_h)
                bot_density = (Bp_h < h_min + 0.08 * h_rng).sum() / len(Bp_h)
                # The rim end will have higher concentration than 8% (the
                # baseline if boundary points were uniformly distributed)
                # because the rim is a continuous smooth arc with many points.
                rim_density = max(top_density, bot_density)
                if rim_density > 0.18:        # strong rim concentration
                    rim_concentration_bonus = 2.0
                elif rim_density > 0.13:      # moderate
                    rim_concentration_bonus = 1.0
                # Also check: is the rim radius (95th percentile of r at the
                # extreme end) similar to what the multi-slice fit predicts?
                # If yes, bonus.
                Bp_rel = Bp - ax_p
                Bp_rvec = Bp_rel - np.outer(Bp_h, ax_d)
                Bp_r = np.linalg.norm(Bp_rvec, axis=1)
                if top_density >= bot_density:
                    extreme_pts = Bp_h > h_max - 0.15 * h_rng
                else:
                    extreme_pts = Bp_h < h_min + 0.15 * h_rng
                if extreme_pts.sum() > 5:
                    extreme_r_p95 = float(np.percentile(Bp_r[extreme_pts], 95))
                    pred_max_r = float(fit["profile_r"].max())
                    if pred_max_r > 0.5:
                        agree = abs(extreme_r_p95 - pred_max_r) / pred_max_r
                        # Boundary 95% radius matches profile max → consistent
                        if agree < 0.10: rim_extreme_bonus = 1.5
                        elif agree < 0.20: rim_extreme_bonus = 0.7

        score = (r_range / max(0.5, rmse)
                 - flat_penalty * 2
                 + rim_concentration_bonus
                 + rim_extreme_bonus)
        if verbose:
            print(f"   axis #{ci+1} {c_norm.round(2)}: rmse={rmse:.3f}, "
                  f"r_range={r_range:.2f}, flat_pen={flat_penalty}, "
                  f"rim_conc={rim_concentration_bonus:.1f}, "
                  f"rim_extr={rim_extreme_bonus:.1f}, score={score:.2f}")
        if score > best_score:
            best_score = score; best_fit = fit

    if best_fit is None:
        return None

    # Step 2: orient axis so rim is up
    best_fit = orient_rim_up(mesh, best_fit)
    axis = best_fit["axis_dir"]; p0 = best_fit["axis_point"]

    # Step 3: identify rim points and compute rim radius (using same logic
    # as the main pipeline — 95th percentile of radii of exterior-facing
    # boundary points in the top 25% of the axis projection)
    loop = get_boundary_loop(mesh)
    rim_radius = None; rim_plane_rmse = None; rim_circle_rmse = None
    rim_pts = None; n_rim_pts = 0
    if len(loop) >= 10:
        Bp = V[loop]; BpN = Nv[loop]
        Bp_rel = Bp - p0
        Bp_H = Bp_rel @ axis
        Bp_rvec = Bp_rel - np.outer(Bp_H, axis)
        Bp_r = np.linalg.norm(Bp_rvec, axis=1)
        rhat = Bp_rvec / np.maximum(Bp_r[:, None], 1e-6)
        ext_facing = (BpN * rhat).sum(axis=1) > 0.3
        h_max_b = Bp_H.max(); h_min_b = Bp_H.min(); h_range_b = h_max_b - h_min_b
        # Rim zone: top 25% of boundary h-range
        rim_zone = Bp_H > h_max_b - 0.25 * h_range_b
        in_rim = ext_facing & rim_zone
        if in_rim.sum() >= 5:
            rim_radius = float(np.percentile(Bp_r[in_rim], 95))
            rim_pts = Bp[in_rim]
            n_rim_pts = int(in_rim.sum())
            # Fit a plane to the rim points to assess planarity
            cen = rim_pts.mean(axis=0)
            pts_c = rim_pts - cen
            try:
                _, _, Vt2 = np.linalg.svd(pts_c, full_matrices=False)
                normal = Vt2[2]
                e_a = Vt2[0]; e_b = Vt2[1]
                pts_2d = np.column_stack([pts_c @ e_a, pts_c @ e_b])
                xc, yc, r_c, c_rmse = fit_circle_2d(pts_2d)
                rim_plane_rmse = float(np.sqrt(np.mean((pts_c @ normal)**2)))
                rim_circle_rmse = float(c_rmse)
                # Compare rim plane normal to axis
                if verbose:
                    delta = np.degrees(np.arccos(abs(np.clip(normal @ axis, -1, 1))))
                    print(f"   Rim plane normal vs axis: {delta:.2f}° apart")
                    print(f"   Rim plane RMSE: {rim_plane_rmse:.3f} cm")
                    print(f"   Rim circle RMSE (TLS plane): {rim_circle_rmse:.3f} cm")
            except Exception:
                rim_plane_rmse = float("nan")
                rim_circle_rmse = float("nan")

    # Quality
    if rim_radius is not None and rim_radius > 0:
        rel_circ = rim_circle_rmse / rim_radius
        rel_plane = rim_plane_rmse / rim_radius
        if rel_circ < 0.04 and rel_plane < 0.04:
            quality = "good"
        elif rel_circ < 0.10 and rel_plane < 0.10:
            quality = "fair"
        else:
            quality = "poor"
    else:
        quality = "poor"
        rim_radius = float(best_fit["profile_r"].max()) if best_fit["profile_r"] is not None else 0.0
        rim_plane_rmse = float("nan")
        rim_circle_rmse = float(best_fit["rmse"])

    inclination_deg = float(np.degrees(np.arccos(
        abs(np.clip(axis @ np.array([0, 1, 0]), -1, 1)))))

    rim_info = {
        "axis_dir": axis,
        "rim_radius": rim_radius,
        "rim_plane_rmse": rim_plane_rmse,
        "rim_circle_rmse": rim_circle_rmse,
        "multi_slice_rmse": float(best_fit["rmse"]),
        "n_rim_points": n_rim_pts,
        "rim_points": rim_pts,
        "quality": quality,
        "inclination_deg": inclination_deg,
        # back-compat
        "plane_residual_rmse": rim_plane_rmse if rim_plane_rmse else float("nan"),
        "circle_residual_rmse": rim_circle_rmse,
    }

    # Compute rotation matrix to align axis with +Y
    target = np.array([0.0, 1.0, 0.0])
    v = np.cross(axis, target)
    s = np.linalg.norm(v)
    c = float(axis @ target)
    if s < 1e-9:
        R = np.eye(3) if c > 0 else np.diag([1.0, -1.0, -1.0])
    else:
        K = np.array([[0, -v[2], v[1]],
                      [v[2], 0, -v[0]],
                      [-v[1], v[0], 0]])
        R = np.eye(3) + K + K @ K * ((1 - c) / (s * s))

    return {
        "R": R,
        "axis_orig": axis,
        "inclination_deg": inclination_deg,
        "rim_info": rim_info,
        "fit": best_fit,
    }
    """
    Automatically determine the vessel axis from the rim geometry, then return
    a rotation matrix that aligns the mesh so its axis is +Y (rim up).

    Returns:
      - R: 3x3 rotation matrix (apply as V_new = V @ R.T)
      - axis_orig: the detected axis in original coordinates
      - rim_info: full output of fit_rim_plane() for diagnostics
    """
    rim_info = fit_rim_plane(mesh, verbose=verbose)
    if rim_info is None:
        return None
    axis = rim_info["axis_dir"] / np.linalg.norm(rim_info["axis_dir"])
    target = np.array([0.0, 1.0, 0.0])  # +Y is "rim up" convention

    # Build rotation matrix that maps `axis` to `target` using Rodrigues' formula
    v = np.cross(axis, target)
    s = np.linalg.norm(v)
    c = float(axis @ target)
    if s < 1e-9:
        # Already aligned (or anti-aligned)
        if c > 0:
            R = np.eye(3)
        else:
            R = np.diag([1.0, -1.0, -1.0])  # 180° flip
    else:
        K = np.array([[0, -v[2], v[1]],
                      [v[2], 0, -v[0]],
                      [-v[1], v[0], 0]])
        R = np.eye(3) + K + K @ K * ((1 - c) / (s * s))

    return {
        "R": R,
        "axis_orig": axis,
        "rim_info": rim_info,
    }


def pick_best_axis(mesh, candidates=None, verbose=False):
    """
    Try several candidate axis directions and pick the one with the best
    combined score:
      - low RMSE of slice fits (goodness of fit)
      - high variation in r(h) (a real vessel has varying radius)
      - penalty for axes along the SHORTEST mesh dimension (scanners often
        place sherds flat; the true axis is almost never the thin direction)
    """
    if candidates is None:
        candidates = [
            np.array([1.0, 0, 0]),
            np.array([0, 1.0, 0]),
            np.array([0, 0, 1.0]),
        ]
    extents = mesh.extents
    short_axis = int(np.argmin(extents))  # index of shortest bbox dim
    best = None; best_score = -np.inf
    for c in candidates:
        try:
            fit = multi_slice_axis_fit(mesh, c, verbose=False)
        except Exception:
            continue
        if fit is None or fit["profile_r"] is None or len(fit["profile_r"]) < 3:
            continue
        rmse = fit["rmse"]
        r_var = float(np.std(fit["profile_r"]))  # std of r(h)
        r_range = float(fit["profile_r"].max() - fit["profile_r"].min())

        # Penalty if the candidate aligns with the shortest mesh dimension
        flat_penalty = 0.0
        c_abs = np.abs(c / np.linalg.norm(c))
        if c_abs[short_axis] > 0.9:
            flat_penalty = 1.0

        # Score: reward variation, penalize RMSE and flatness
        # (higher = better)
        score = r_range / max(0.5, rmse) - flat_penalty * 2
        if verbose:
            print(f"   try axis {c}: RMSE {rmse:.2f}  r_range {r_range:.2f}  "
                  f"flat_pen {flat_penalty}  score {score:.2f}")
        if score > best_score:
            best_score = score; best = fit
    return best


def orient_rim_up(mesh, fit):
    """
    Ensure the axis direction points from base toward rim (so that max(H) = rim).
    The rim is identified as the end of the mesh with a more circular boundary
    (fractures are irregular; rims fit a circle well).
    """
    V = np.asarray(mesh.vertices)
    H_all = (V - fit["axis_point"]) @ fit["axis_dir"]
    H_top = H_all.max(); H_bot = H_all.min()
    # Fit circles to the top and bottom boundary bands
    loop = get_boundary_loop(mesh)
    if len(loop) == 0:
        return fit
    Bp = V[loop]
    Bp_H = (Bp - fit["axis_point"]) @ fit["axis_dir"]
    # Top-band vs bottom-band boundary points
    band = (H_top - H_bot) * 0.05
    top_pts = Bp[Bp_H > H_top - band]
    bot_pts = Bp[Bp_H < H_bot + band]
    def resid(pts):
        if len(pts) < 8: return np.inf
        ca = (pts - fit["axis_point"]) @ fit["e_a"]
        cb = (pts - fit["axis_point"]) @ fit["e_b"]
        _, _, _, res = fit_circle_2d(np.column_stack([ca, cb]))
        return res
    r_top = resid(top_pts); r_bot = resid(bot_pts)
    if r_bot < r_top:
        # Bottom boundary is more circular → it's actually the rim → flip axis
        fit["axis_dir"] = -fit["axis_dir"]
        fit["profile_h"] = -fit["profile_h"][::-1]
        fit["profile_r"] = fit["profile_r"][::-1]
    return fit


# =============================================================================
# Cross-section extraction
# =============================================================================

def find_best_section_azimuth(mesh, fit, n_azimuths=36, h_range_min_frac=0.85):
    """Sweep azimuths and return the one giving the THINNEST clean section.

    The previous heuristic picked the azimuth with the longest H-range
    (most material vertically). On wide-arc sherds (≥120° preserved), this
    fails: all azimuths catch the full vertical extent, but slicing planes
    that run ALONG the curved wall (parallel to its tangent) catch wall
    material at multiple radial positions, producing a doubled-thickness
    section silhouette that misrepresents the actual wall profile.

    The correct slicing direction is RADIAL through the preserved arc's
    angular center, where the slicing plane intersects the wall
    perpendicular to its surface. The thinnest section polygon (smallest
    median wall width) corresponds to this perpendicular cut.

    Algorithm:
      1. Sweep n_azimuths candidates uniformly around 360°
      2. For each, extract the section polygon and measure its median
         wall thickness (median width across vertical bands)
      3. Reject candidates whose H-range is much shorter than the maximum
         (so we don't pick a degenerate sliver that just happens to be thin)
      4. Among candidates passing the H-range filter, return the one
         with the smallest median thickness

    Returns (az_deg, radial_dir, sec) or None.
    """
    axis = fit["axis_dir"]; p0 = fit["axis_point"]
    e_a, e_b = fit["e_a"], fit["e_b"]
    candidates = []  # list of (az_deg, radial_dir, sec, h_range, median_width)
    max_h_range = 0.0
    for az_deg in np.linspace(0, 360, n_azimuths, endpoint=False):
        az = np.radians(az_deg)
        rdir = e_a * np.cos(az) + e_b * np.sin(az)
        n = np.cross(axis, rdir); n /= np.linalg.norm(n)
        try:
            sec = mesh.section(plane_origin=p0, plane_normal=n)
        except Exception:
            continue
        if sec is None: continue
        try:
            pts = np.vstack([e.discrete(sec.vertices).reshape(-1, 3) for e in sec.entities])
        except Exception:
            continue
        if len(pts) < 15: continue
        pv = pts - p0
        h = pv @ axis
        # Perpendicular distance from axis (= radial position in the section plane)
        r_radial = pv @ rdir
        h_range = float(h.max() - h.min())
        if h_range > max_h_range:
            max_h_range = h_range
        # Median wall width across n_bands vertical bands.
        # Width = max - min of radial position within each band.
        # For a clean wall section this is small (the wall thickness).
        # For a section running along the curved wall surface, this is
        # large (catches material on multiple radial positions).
        n_bands = 12
        h_grid = np.linspace(h.min(), h.max(), n_bands + 1)
        widths = []
        for i in range(n_bands):
            in_band = (h >= h_grid[i]) & (h < h_grid[i+1])
            if in_band.sum() >= 2:
                widths.append(float(r_radial[in_band].max()
                                    - r_radial[in_band].min()))
        if not widths:
            continue
        median_width = float(np.median(widths))
        candidates.append((float(az_deg), rdir, sec, h_range, median_width))

    if not candidates:
        return None

    # Filter to candidates with H-range close to the max (don't pick a
    # degenerate sliver that's thin only because it caught little material)
    h_threshold = max_h_range * h_range_min_frac
    survivors = [c for c in candidates if c[3] >= h_threshold]
    if not survivors:
        survivors = candidates

    # Among survivors, pick the THINNEST (smallest median wall width)
    best = min(survivors, key=lambda c: c[4])
    return (best[0], best[1], best[2])


def extract_section_at_azimuth(mesh, fit, az_deg):
    """Extract a section polygon at a specific azimuth angle.

    Used by both the multi-azimuth consensus extractor below and by the
    GUI's manual azimuth-override slider. Returns (radial_dir, sec) on
    success, None on failure.
    """
    axis = fit["axis_dir"]; p0 = fit["axis_point"]
    e_a, e_b = fit["e_a"], fit["e_b"]
    az = np.radians(az_deg)
    rdir = e_a * np.cos(az) + e_b * np.sin(az)
    n = np.cross(axis, rdir); n /= np.linalg.norm(n)
    try:
        sec = mesh.section(plane_origin=p0, plane_normal=n)
    except Exception:
        return None
    if sec is None:
        return None
    return rdir, sec


def extract_median_consensus_section(mesh, fit, base_az_deg, sweep_deg=15.0,
                                       n_samples=7):
    """Extract a robust section by taking the median across multiple
    nearby azimuths.

    The single-azimuth section is sensitive to local mesh anomalies:
    interior curls, surface bumps, mesh holes. Slicing at slightly
    different angles often produces different anomalies, but the wall
    profile itself is consistent. By sampling N azimuths within a
    ±sweep range around the chosen base angle and taking the median
    radial position at each height, we get a section that's robust to
    any single slice's anomalies.

    Args:
        mesh: trimesh Trimesh
        fit: axis fit dict
        base_az_deg: center azimuth (typically result of
            find_best_section_azimuth, optionally adjusted by user)
        sweep_deg: total sweep range (default ±15° = 30° span)
        n_samples: number of azimuths to sample within the sweep

    Returns:
        (median_section_polygon, base_az_deg) or None if failure.
        median_section_polygon has shape (M, 2) in (r, h) coords like
        the regular section polygon, ready for the same downstream
        rendering code.
    """
    if n_samples < 3:
        # Fall back to single section
        result = extract_section_at_azimuth(mesh, fit, base_az_deg)
        if result is None:
            return None
        rdir, sec = result
        poly, _area = extract_section_polygon(sec, fit["axis_point"],
                                                fit["axis_dir"], rdir)
        if poly is None or len(poly) < 3:
            return None
        return poly, base_az_deg

    # Sample azimuths uniformly within the sweep range
    half_sweep = sweep_deg / 2.0
    sample_azs = np.linspace(base_az_deg - half_sweep,
                              base_az_deg + half_sweep, n_samples)

    polygons = []
    for az in sample_azs:
        result = extract_section_at_azimuth(mesh, fit, az)
        if result is None:
            continue
        rdir, sec = result
        poly, _area = extract_section_polygon(sec, fit["axis_point"],
                                                fit["axis_dir"], rdir)
        if poly is None or len(poly) < 3:
            continue
        polygons.append(poly)

    if len(polygons) < 2:
        # Not enough valid samples — return single best if possible
        if polygons:
            return polygons[0], base_az_deg
        return None

    # ── Median consensus over a common height grid ──
    # All polygons are in (r, h) coords. For each height h_i in a common
    # grid, find the "outer wall" radius across all polygons (the maximum
    # r at that height for points facing outward). Take the median across
    # polygons. Same for inner wall.
    all_h = np.concatenate([p[:, 1] for p in polygons])
    h_min, h_max = float(all_h.min()), float(all_h.max())
    n_grid = 80
    h_grid = np.linspace(h_min, h_max, n_grid)

    outer_rs = []  # shape: (n_polygons, n_grid)
    inner_rs = []  # shape: (n_polygons, n_grid)

    for poly in polygons:
        # Sort by h for monotonic lookup; then for each h_i, collect all
        # r values from poly within a small h-band, and take min/max.
        outer_per_h = np.full(n_grid, np.nan)
        inner_per_h = np.full(n_grid, np.nan)
        h_band = (h_max - h_min) / n_grid * 1.5
        for i, h_i in enumerate(h_grid):
            band_mask = np.abs(poly[:, 1] - h_i) < h_band
            if band_mask.sum() >= 2:
                rs_in_band = poly[band_mask, 0]
                outer_per_h[i] = float(rs_in_band.max())
                inner_per_h[i] = float(rs_in_band.min())
        outer_rs.append(outer_per_h)
        inner_rs.append(inner_per_h)

    outer_rs = np.array(outer_rs)
    inner_rs = np.array(inner_rs)

    # Median across polygons (axis=0). NaN-aware: ignore polygons that
    # have no data at a given height.
    with np.errstate(invalid='ignore'):
        outer_median = np.nanmedian(outer_rs, axis=0)
        inner_median = np.nanmedian(inner_rs, axis=0)

    # Build a polygon: outer wall going up, inner wall coming back down.
    # Drop heights where either is NaN (no data).
    valid = np.isfinite(outer_median) & np.isfinite(inner_median)
    if valid.sum() < 5:
        # Not enough valid grid points — fall back to first polygon
        return polygons[0], base_az_deg

    h_valid = h_grid[valid]
    outer_valid = outer_median[valid]
    inner_valid = inner_median[valid]

    # Build polygon: outer ascending, then inner descending (closed loop)
    outer_pts = np.column_stack([outer_valid, h_valid])
    inner_pts = np.column_stack([inner_valid[::-1], h_valid[::-1]])
    median_poly = np.vstack([outer_pts, inner_pts, outer_pts[:1]])

    return median_poly, base_az_deg


def extract_section_polygon(sec, p0, axis, radial_dir):
    """Convert a trimesh section into a 2D polygon in (r, h) coords."""
    polys = []
    for ent in sec.entities:
        v3 = sec.vertices[ent.points]
        pv = v3 - p0
        # We want sherd on POSITIVE r side, so test which side the data is on
        r_signed = pv @ radial_dir
        h = pv @ axis
        # We'll flip sign of r later if needed to make sherd appear on positive r
        polys.append(np.column_stack([r_signed, h]))
    # Pick largest-area polygon
    best_p = None; best_area = 0
    for p in polys:
        if len(p) < 3: continue
        try:
            a = Polygon(p).area
            if a > best_area:
                best_area = a; best_p = p
        except Exception:
            continue
    if best_p is None: return None, 0
    # Flip sign so sherd is on positive r
    if np.median(best_p[:, 0]) < 0:
        best_p = np.column_stack([-best_p[:, 0], best_p[:, 1]])
    return best_p, best_area


def compute_preserved_arc(mesh, fit):
    """Degrees of rim circumference preserved. Uses only exterior-facing
    boundary points near the top so that interior-lip strips (for everted rims)
    don't inflate the count."""
    V = np.asarray(mesh.vertices); Nv = np.asarray(mesh.vertex_normals)
    loop = get_boundary_loop(mesh)
    if len(loop) == 0: return 0.0
    Bp = V[loop]; BpN = Nv[loop]
    rel = Bp - fit["axis_point"]
    H_bp = rel @ fit["axis_dir"]
    rvec = rel - np.outer(H_bp, fit["axis_dir"])
    r_bp = np.linalg.norm(rvec, axis=1)
    rhat = rvec / np.maximum(r_bp[:, None], 1e-6)
    ext_facing = (BpN * rhat).sum(axis=1) > 0.3

    H_max = H_bp.max()
    h_range = H_bp.max() - H_bp.min()
    band = max(1.0, h_range * 0.08)
    rim_mask = ext_facing & (H_bp > H_max - band)
    if rim_mask.sum() < 5:
        # fallback: use top 5% without filtering
        rim_mask = H_bp > H_max - h_range * 0.05
        if rim_mask.sum() < 5: return 0.0

    rp = Bp[rim_mask]
    a = (rp - fit["axis_point"]) @ fit["e_a"]
    b = (rp - fit["axis_point"]) @ fit["e_b"]
    thetas = np.arctan2(b, a)
    ts = np.sort(thetas)
    gaps = np.diff(np.concatenate([ts, ts[:1] + 2*np.pi]))
    return float(np.degrees(2*np.pi - gaps.max()))


# =============================================================================
# Plate rendering (the archaeological drawing)
# =============================================================================

def extract_outer_wall(section_poly):
    """
    From a closed section polygon, extract the OUTER wall trace as a CONTINUOUS
    open polyline. The section polygon walks as: outer-wall going up, across the
    rim, inner-wall going down, across the broken bottom edge, back to start.
    We want only the outer-wall arc.

    Strategy: at each height h within the polygon's range, find ALL polygon
    crossings of that horizontal line. Take the MAXIMUM-r crossing as the
    outer-wall point. This gives a complete outer-wall polyline with no gaps.

    Returns the outer-wall polyline as Nx2 array (r,h), ordered bottom→top.
    """
    n = len(section_poly)
    if n < 4:
        return None
    rs = section_poly[:, 0]
    hs = section_poly[:, 1]
    h_min, h_max = float(hs.min()), float(hs.max())
    if h_max - h_min < 0.5:
        return None

    # Sample n_samples horizontal lines spanning the section's height range,
    # avoiding the very edges where intersections may be unstable.
    n_samples = 80
    h_samples = np.linspace(h_min + 0.01, h_max - 0.01, n_samples)

    outer_points = []
    for h in h_samples:
        # Find all polygon edges that cross y=h
        crossings_r = []
        for i in range(n):
            j = (i + 1) % n
            h0, h1 = hs[i], hs[j]
            if (h0 - h) * (h1 - h) > 0:
                continue  # both on same side, no crossing
            if abs(h1 - h0) < 1e-9:
                continue  # nearly horizontal edge, skip
            t = (h - h0) / (h1 - h0)
            if 0 <= t <= 1:
                r_cross = rs[i] + t * (rs[j] - rs[i])
                crossings_r.append(r_cross)
        if len(crossings_r) >= 1:
            # Outer wall = maximum r at this height
            outer_points.append((max(crossings_r), float(h)))

    if len(outer_points) < 3:
        return None
    outer = np.array(outer_points)
    # Already sorted bottom→top by construction (h_samples is increasing)
    return outer


def extract_outer_profile(section_poly):
    """Backward-compatible alias."""
    return extract_outer_wall(section_poly)


def extract_inner_wall(section_poly):
    """
    From a closed section polygon, extract the INNER wall trace as a CONTINUOUS
    open polyline. Take the MINIMUM-r crossing as the inner-wall point.
    """
    n = len(section_poly)
    if n < 4:
        return None
    rs = section_poly[:, 0]
    hs = section_poly[:, 1]
    h_min, h_max = float(hs.min()), float(hs.max())
    if h_max - h_min < 0.5:
        return None

    n_samples = 80
    h_samples = np.linspace(h_min + 0.01, h_max - 0.01, n_samples)

    inner_points = []
    for h in h_samples:
        crossings_r = []
        for i in range(n):
            j = (i + 1) % n
            h0, h1 = hs[i], hs[j]
            if (h0 - h) * (h1 - h) > 0:
                continue
            if abs(h1 - h0) < 1e-9:
                continue
            t = (h - h0) / (h1 - h0)
            if 0 <= t <= 1:
                r_cross = rs[i] + t * (rs[j] - rs[i])
                crossings_r.append(r_cross)
        if len(crossings_r) >= 1:
            inner_points.append((min(crossings_r), float(h)))

    if len(inner_points) < 3:
        return None
    inner = np.array(inner_points)
    return inner


def extract_walls_and_thickness(section_poly, n_samples=80):
    """
    Extract aligned outer wall, inner wall and thickness from section polygon.
    """
    n = len(section_poly)
    if n < 4:
        return None, None, None
    rs = section_poly[:, 0]
    hs = section_poly[:, 1]
    h_min, h_max = float(hs.min()), float(hs.max())
    if h_max - h_min < 0.5:
        return None, None, None

    h_samples = np.linspace(h_min + 0.01, h_max - 0.01, n_samples)

    outer_points = []
    inner_points = []
    for h in h_samples:
        crossings_r = []
        for i in range(n):
            j = (i + 1) % n
            h0, h1 = hs[i], hs[j]
            if (h0 - h) * (h1 - h) > 0:
                continue
            if abs(h1 - h0) < 1e-9:
                continue
            t = (h - h0) / (h1 - h0)
            if 0 <= t <= 1:
                r_cross = rs[i] + t * (rs[j] - rs[i])
                crossings_r.append(r_cross)
        if len(crossings_r) >= 2:
            outer_points.append((max(crossings_r), float(h)))
            inner_points.append((min(crossings_r), float(h)))
        elif len(crossings_r) == 1:
            outer_points.append((crossings_r[0], float(h)))
            inner_points.append((crossings_r[0], float(h)))

    if len(outer_points) < 3:
        return None, None, None
        
    outer = np.array(outer_points)
    inner = np.array(inner_points)
    thickness = outer[:, 0] - inner[:, 0]
    return outer, inner, thickness


def compute_parametric_curvature(points, window_length=15, polyorder=2):
    """
    Compute curvature of a 2D curve using a parametric representation
    and Savitzky-Golay filtering for numerical derivatives.
    """
    N = len(points)
    if N < 5:
        return np.zeros(N)
    
    w = window_length
    if w >= N:
        w = N - 1
        if w % 2 == 0:
            w -= 1
    if w < 3:
        w = 3
        
    r = points[:, 0]
    h = points[:, 1]
    
    # Derivatives with respect to the index parameter t (dt = 1)
    dr = savgol_filter(r, w, polyorder, deriv=1)
    ddr = savgol_filter(r, w, polyorder, deriv=2)
    
    dh = savgol_filter(h, w, polyorder, deriv=1)
    ddh = savgol_filter(h, w, polyorder, deriv=2)
    
    denom = (dr**2 + dh**2)**1.5
    denom = np.where(denom < 1e-9, 1e-9, denom)
    
    # Signed curvature
    kappa = (dr * ddh - dh * ddr) / denom
    return kappa


def plot_sherd_curvature_thickness(section_poly, title="Sherd Curvature & Thickness Analysis", save_path=None, fig=None, mesh=None, fit=None, az_deg=None, plot_mode="both"):
    """
    Generate and display or save a beautiful Matplotlib figure analyzing:
    - Width/thickness of the sherd wall.
    - External wall curvature (red).
    - Internal wall curvature (blue).
    - 3D visualization showing the sherd and its curvature profiles in space.
    """
    import matplotlib.pyplot as plt
    
    outer, inner, thickness = extract_walls_and_thickness(section_poly)
    if outer is None or inner is None:
        raise ValueError("Could not extract walls and thickness from the section polygon.")
        
    # Compute parametric curvature for outer and inner walls
    kappa_outer = compute_parametric_curvature(outer)
    kappa_inner = compute_parametric_curvature(inner)
    
    # Use absolute curvature for simplicity/interpretability
    abs_kappa_outer = np.abs(kappa_outer)
    abs_kappa_inner = np.abs(kappa_inner)
    
    # Create or clear the figure
    if fig is None:
        if plot_mode == "2d":
            fig = plt.figure(figsize=(12, 5))
        elif plot_mode == "3d":
            fig = plt.figure(figsize=(6, 5))
        else: # both
            fig = plt.figure(figsize=(16, 5) if (mesh is not None) else (12, 5))
    else:
        fig.clear()
        if plot_mode == "2d":
            fig.set_size_inches(12, 5)
        elif plot_mode == "3d":
            fig.set_size_inches(6, 5)
        else: # both
            fig.set_size_inches(16, 5) if (mesh is not None) else fig.set_size_inches(12, 5)
        
    fig.suptitle(title, fontsize=15, fontweight="bold", y=0.98)
    
    # Create subplots based on plot_mode
    ax1, ax2, ax3, ax4 = None, None, None, None
    if plot_mode == "2d":
        ax1 = fig.add_subplot(1, 3, 1)
        ax2 = fig.add_subplot(1, 3, 2)
        ax3 = fig.add_subplot(1, 3, 3)
    elif plot_mode == "3d":
        if mesh is not None and fit is not None and az_deg is not None:
            ax4 = fig.add_subplot(1, 1, 1, projection='3d')
        else:
            raise ValueError("Mesh, fit, and az_deg must be provided for 3D plotting.")
    else: # both
        if mesh is not None and fit is not None and az_deg is not None:
            ax1 = fig.add_subplot(1, 4, 1)
            ax2 = fig.add_subplot(1, 4, 2)
            ax3 = fig.add_subplot(1, 4, 3)
            ax4 = fig.add_subplot(1, 4, 4, projection='3d')
        else:
            ax1 = fig.add_subplot(1, 3, 1)
            ax2 = fig.add_subplot(1, 3, 2)
            ax3 = fig.add_subplot(1, 3, 3)
    
    # Align Y-limits to be identical across all three 2D panels
    if ax1 is not None and ax2 is not None and ax3 is not None:
        h_min = float(outer[:, 1].min())
        h_max = float(outer[:, 1].max())
        padding = float(max(0.1, (h_max - h_min) * 0.05))
        
        for ax in (ax1, ax2, ax3):
            ax.set_ylim(h_min - padding, h_max + padding)
        
        # Subplot 1: Profile & Extracted Walls
        ax1.fill(section_poly[:, 0].tolist(), section_poly[:, 1].tolist(), color="#e8e8e8", edgecolor="#7f7f7f", alpha=0.5, label="Full Profile")
        ax1.plot(outer[:, 0].tolist(), outer[:, 1].tolist(), color="#ff3b30", lw=3.0, label="External Wall (Red)")
        ax1.plot(inner[:, 0].tolist(), inner[:, 1].tolist(), color="#007aff", lw=3.0, label="Internal Wall (Blue)")
        ax1.set_title("Profile & Walls", fontsize=12, fontweight="semibold")
        ax1.set_xlabel("Radius r (cm)", fontsize=10)
        ax1.set_ylabel("Height h (cm)", fontsize=10)
        ax1.grid(True, linestyle=":", alpha=0.6)
        ax1.legend(loc="upper right", framealpha=0.9)
        
        # Subplot 2: Wall Thickness (Width)
        ax2.plot(thickness.tolist(), outer[:, 1].tolist(), color="#5856d6", lw=2.5, label="Thickness (cm)")
        
        # Mark min/max values
        min_idx = int(np.argmin(thickness))
        max_idx = int(np.argmax(thickness))
        mean_thick = float(np.mean(thickness))
        
        ax2.plot(float(thickness[min_idx]), float(outer[min_idx, 1]), "o", color="#34c759", markersize=7)
        ax2.plot(float(thickness[max_idx]), float(outer[max_idx, 1]), "o", color="#ff9500", markersize=7)
        
        ax2.text(float(thickness[min_idx]) + 0.05, float(outer[min_idx, 1]), f"Min: {thickness[min_idx]*10:.1f} mm", 
                 verticalalignment="center", fontsize=9, fontweight="bold", color="#248a3d")
        ax2.text(float(thickness[max_idx]) + 0.05, float(outer[max_idx, 1]), f"Max: {thickness[max_idx]*10:.1f} mm", 
                 verticalalignment="center", fontsize=9, fontweight="bold", color="#cc7600")
        
        ax2.axvline(mean_thick, color="#5856d6", linestyle="--", alpha=0.7, label=f"Mean: {mean_thick*10:.1f} mm")
        
        ax2.set_title("Wall Thickness (Width)", fontsize=12, fontweight="semibold")
        ax2.set_xlabel("Thickness (cm)", fontsize=10)
        ax2.grid(True, linestyle=":", alpha=0.6)
        ax2.legend(loc="upper right", framealpha=0.9)
        
        # Subplot 3: External & Internal Curvature
        ax3.plot(abs_kappa_outer.tolist(), outer[:, 1].tolist(), color="#ff3b30", lw=2.5, label="External Curvature (Red)")
        ax3.plot(abs_kappa_inner.tolist(), inner[:, 1].tolist(), color="#007aff", lw=2.5, label="Internal Curvature (Blue)")
        
        # Mark peak curvatures
        peak_out_idx = int(np.argmax(abs_kappa_outer))
        peak_in_idx = int(np.argmax(abs_kappa_inner))
        
        ax3.plot(float(abs_kappa_outer[peak_out_idx]), float(outer[peak_out_idx, 1]), "ro", markersize=7)
        ax3.plot(float(abs_kappa_inner[peak_in_idx]), float(inner[peak_in_idx, 1]), "bo", markersize=7)
        
        ax3.text(float(abs_kappa_outer[peak_out_idx]) + 0.05, float(outer[peak_out_idx, 1]), f"Peak Ext: {abs_kappa_outer[peak_out_idx]:.2f}", 
                 verticalalignment="center", color="#ff3b30", fontsize=9, fontweight="bold")
        ax3.text(float(abs_kappa_inner[peak_in_idx]) + 0.05, float(inner[peak_in_idx, 1]), f"Peak Int: {abs_kappa_inner[peak_in_idx]:.2f}", 
                 verticalalignment="center", color="#007aff", fontsize=9, fontweight="bold")
        
        ax3.set_title("Curvature vs Height", fontsize=12, fontweight="semibold")
        ax3.set_xlabel(r"Curvature $\kappa$ ($1/\mathrm{cm}$)", fontsize=10)
        ax3.grid(True, linestyle=":", alpha=0.6)
        ax3.legend(loc="upper right", framealpha=0.9)
        
    # Subplot 4: 3D Visualization of Sherd and Curvatures
    if ax4 is not None:
        try:
            # 1. Subsample faces manually for fast plotting (pure Python/NumPy, no dependencies like fast_simplification)
            num_faces = len(mesh.faces)
            if num_faces > 1500:
                step = num_faces // 1500
                faces_to_plot = mesh.faces[::step]
            else:
                faces_to_plot = mesh.faces
                
            from mpl_toolkits.mplot3d.art3d import Poly3DCollection, Line3DCollection
            
            triangles = mesh.vertices[faces_to_plot]
            # Highly visible terracotta representation for the sherd
            poly3d = Poly3DCollection(triangles, alpha=0.6, facecolor='#d27d50', edgecolor='#5a2d1e', linewidths=0.5)
            ax4.add_collection3d(poly3d)
            
            # 2. Get 3D curves for external & internal walls
            axis_point = np.array(fit["axis_point"])
            axis_dir = np.array(fit["axis_dir"])
            
            # Check if the fitted axis is degenerate (passing too close to the sherd)
            centroid = mesh.vertices.mean(axis=0)
            rel_c = centroid - axis_point
            dist_to_axis = np.linalg.norm(rel_c - np.dot(rel_c, axis_dir) * axis_dir)
            
            # If the axis is closer to the sherd than 15 cm, it's a degenerate fit.
            # Fall back to the global Y-axis passing through the origin.
            is_degenerate = dist_to_axis < 15.0 or fit.get("rmse", 0) > 2.0
            
            if is_degenerate:
                axis_point_3d = np.array([0.0, 0.0, 0.0])
                axis_dir_3d = np.array([0.0, 1.0, 0.0])
                e_a = np.array([1.0, 0.0, 0.0])
                e_b = np.array([0.0, 0.0, 1.0])
                az_rad = np.arctan2(centroid[2], centroid[0])
                true_radius = np.sqrt(centroid[0]**2 + centroid[2]**2)
                shift = true_radius - dist_to_axis
            else:
                axis_point_3d = axis_point
                axis_dir_3d = axis_dir
                tmp = (np.array([1.0, 0, 0]) if abs(axis_dir_3d[0]) < 0.9 else np.array([0, 1.0, 0]))
                e_a = np.cross(axis_dir_3d, tmp)
                e_a /= np.linalg.norm(e_a)
                e_b = np.cross(axis_dir_3d, e_a)
                e_b /= np.linalg.norm(e_b)
                az_rad = np.radians(az_deg)
                shift = 0.0
                
            rad_dir = np.cos(az_rad) * e_a + np.sin(az_rad) * e_b
            
            # Compute 3D coordinates of the section curves
            outer_3d = axis_point_3d + np.outer(outer[:, 1], axis_dir_3d) + np.outer(outer[:, 0] + shift, rad_dir)
            inner_3d = axis_point_3d + np.outer(inner[:, 1], axis_dir_3d) + np.outer(inner[:, 0] + shift, rad_dir)
            
            # Reconstruct the 3D curvature grids (continuation of the sherd)
            # Find the angular span of the sherd
            V_rel = mesh.vertices - axis_point_3d
            V_h = V_rel @ axis_dir_3d
            V_proj = V_rel - np.outer(V_h, axis_dir_3d)
            V_a = V_proj @ e_a
            V_b = V_proj @ e_b
            V_angles = np.arctan2(V_b, V_a)
            rel_angles = np.arctan2(np.sin(V_angles - az_rad), np.cos(V_angles - az_rad))
            theta_min = float(np.percentile(rel_angles, 0.5))
            theta_max = float(np.percentile(rel_angles, 99.5))
            
            span = theta_max - theta_min
            # Extend angular span by 1.2 radians (~70 degrees) on each side to show the circular rim clearly
            ext_angle = max(1.2, span * 0.5)
            theta_start = theta_min - ext_angle
            theta_end = theta_max + ext_angle
            if theta_end - theta_start > 2 * np.pi:
                theta_start = -np.pi
                theta_end = np.pi
                
            # Height span and extension: only extend downwards (below h_min), do not exceed h_max (the rim)
            h_min = float(outer[:, 1].min())
            h_max = float(outer[:, 1].max())
            h_span = h_max - h_min
            h_ext = h_span * 0.30
            h_start = h_min - h_ext
            h_end = h_max
            
            from scipy.interpolate import interp1d
            interp_r_out = interp1d(outer[:, 1], outer[:, 0], kind='linear', fill_value='extrapolate')
            interp_k_out = interp1d(outer[:, 1], abs_kappa_outer, kind='linear', fill_value='extrapolate')
            interp_r_in = interp1d(inner[:, 1], inner[:, 0], kind='linear', fill_value='extrapolate')
            interp_k_in = interp1d(inner[:, 1], abs_kappa_inner, kind='linear', fill_value='extrapolate')
            
            def get_r_out(h):
                return np.maximum(interp_r_out(h), 0.1) + shift
            def get_r_in(h):
                return np.maximum(interp_r_in(h), 0.1) + shift
                
            max_k = max(float(np.max(abs_kappa_outer)), float(np.max(abs_kappa_inner)), 0.5)
            global_norm = plt.Normalize(vmin=0, vmax=max_k)
            
            # Plot 3D curves colored by curvature using global normalization
            def plot_colored_3d_curve(ax, pts3d, k_vals, cmap_name, label, linewidth=1.2):
                points = pts3d.reshape(-1, 1, 3)
                segments = np.concatenate([points[:-1], points[1:]], axis=1)
                lc = Line3DCollection(segments, cmap=cmap_name, norm=global_norm, linewidths=linewidth, label=label)
                lc.set_array(np.abs(k_vals))
                ax.add_collection3d(lc)
                return lc
                
            # Generate grid lines
            num_meridians = 9
            num_parallels = 9
            theta_grid = np.linspace(theta_start, theta_end, num_meridians)
            h_grid = np.linspace(h_start, h_end, num_parallels)
            
            # Draw external wall grid
            h_fine = np.linspace(h_start, h_end, 50)
            r_fine_out = get_r_out(h_fine)
            k_fine_out = np.abs(interp_k_out(h_fine))
            for t in theta_grid:
                rad_dir_t = np.cos(az_rad + t) * e_a + np.sin(az_rad + t) * e_b
                pts_meridian = axis_point_3d + np.outer(h_fine, axis_dir_3d) + np.outer(r_fine_out, rad_dir_t)
                plot_colored_3d_curve(ax4, pts_meridian, k_fine_out, 'plasma', 'Ext Grid Meridian', linewidth=1.0)
                
            theta_fine = np.linspace(theta_start, theta_end, 50)
            rad_dirs_fine = np.outer(np.cos(az_rad + theta_fine), e_a) + np.outer(np.sin(az_rad + theta_fine), e_b)
            for h in h_grid:
                r_h = get_r_out(h)
                k_h = np.abs(interp_k_out(h))
                pts_parallel = axis_point_3d + h * axis_dir_3d + r_h * rad_dirs_fine
                k_parallel = np.full(len(theta_fine), k_h)
                plot_colored_3d_curve(ax4, pts_parallel, k_parallel, 'plasma', 'Ext Grid Parallel', linewidth=1.0)
                
            # Draw internal wall grid
            r_fine_in = get_r_in(h_fine)
            k_fine_in = np.abs(interp_k_in(h_fine))
            for t in theta_grid:
                rad_dir_t = np.cos(az_rad + t) * e_a + np.sin(az_rad + t) * e_b
                pts_meridian = axis_point_3d + np.outer(h_fine, axis_dir_3d) + np.outer(r_fine_in, rad_dir_t)
                plot_colored_3d_curve(ax4, pts_meridian, k_fine_in, 'plasma', 'Int Grid Meridian', linewidth=1.0)
                
            for h in h_grid:
                r_h = get_r_in(h)
                k_h = np.abs(interp_k_in(h))
                pts_parallel = axis_point_3d + h * axis_dir_3d + r_h * rad_dirs_fine
                k_parallel = np.full(len(theta_fine), k_h)
                plot_colored_3d_curve(ax4, pts_parallel, k_parallel, 'plasma', 'Int Grid Parallel', linewidth=1.0)
                
            # Highlight main profile curves (thicker lines)
            lc_out = plot_colored_3d_curve(ax4, outer_3d, abs_kappa_outer, 'plasma', 'Ext Curvature', linewidth=3.5)
            plot_colored_3d_curve(ax4, inner_3d, abs_kappa_inner, 'plasma', 'Int Curvature', linewidth=3.5)
            
            # Add a colorbar for curvature
            cb = fig.colorbar(lc_out, ax=ax4, shrink=0.6, aspect=12, pad=0.1)
            cb.set_label('Curvature (1/cm)', fontsize=9)
            cb.ax.tick_params(labelsize=8)
            
            # Adjust 3D aspect ratio and viewing angle to fit the extended grid points
            grid_pts = []
            for t in [theta_start, theta_end]:
                rad_dir_t = np.cos(az_rad + t) * e_a + np.sin(az_rad + t) * e_b
                for h in [h_start, h_end]:
                    grid_pts.append(axis_point_3d + h * axis_dir_3d + get_r_out(h) * rad_dir_t)
                    grid_pts.append(axis_point_3d + h * axis_dir_3d + get_r_in(h) * rad_dir_t)
            
            all_pts = np.concatenate([mesh.vertices, outer_3d, inner_3d, np.array(grid_pts)], axis=0)
            mins = all_pts.min(axis=0)
            maxs = all_pts.max(axis=0)
            centers = (mins + maxs) / 2.0
            max_range = (maxs - mins).max() / 2.0
            
            ax4.set_xlim3d(centers[0] - max_range, centers[0] + max_range)
            ax4.set_ylim3d(centers[1] - max_range, centers[1] + max_range)
            ax4.set_zlim3d(centers[2] - max_range, centers[2] + max_range)
            
            ax4.set_title("3D Curvature Profile", fontsize=12, fontweight="semibold")
            ax4.set_xlabel("X (cm)", fontsize=8)
            ax4.set_ylabel("Y (cm)", fontsize=8)
            ax4.set_zlabel("Z (cm)", fontsize=8)
            ax4.tick_params(labelsize=7)
            
            ax4.view_init(elev=20, azim=45)
            
        except Exception as e:
            ax4.text2D(0.1, 0.5, f"3D plot failed: {e}", transform=ax4.transAxes, color='red')

    fig.tight_layout()
    
    if save_path:
        fig.savefig(str(save_path), dpi=150)
        plt.close(fig)
        
        # Save raw values to a matching CSV file
        csv_path = Path(save_path).with_suffix(".csv")
        try:
            import csv
            with open(csv_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.writer(f)
                writer.writerow([
                    "Height (cm)", 
                    "Outer_Radius (cm)", 
                    "Inner_Radius (cm)", 
                    "Thickness (cm)", 
                    "External_Curvature (1/cm)", 
                    "Internal_Curvature (1/cm)"
                ])
                for i in range(len(outer)):
                    writer.writerow([
                        float(outer[i, 1]),
                        float(outer[i, 0]),
                        float(inner[i, 0]),
                        float(thickness[i]),
                        float(abs_kappa_outer[i]),
                        float(abs_kappa_inner[i])
                    ])
        except Exception as e:
            pass
    elif fig is None:
        plt.show()


def render_plate(mesh, fit, section_poly, section_az_deg, result, out_png, out_svg,
                  sherd_dx=0.0, sherd_dy=0.0, sherd_scale=1.0,
                  flip_view=False, flip_rim=False, rim_incl_deg=0.0,
                  view_rot_deg=0.0, image_rot_deg=0.0, unroll_mode=False,
                  light_az_deg=0.0, light_el_deg=20.0, contrast=1.0,
                  use_vertex_colors=False,
                  user_oriented=False, fig=None, gpu_render=False,
                  hide_sherd=False):
    # Per-stage timing — populated as we go through the function. The GUI
    # reads _LAST_TIMING after each render and prints a one-line summary
    # to its log pane.
    import time as _time
    _t_render_start = _time.perf_counter()
    _stage_t = {}
    def _stage(name):
        _stage_t[name] = _time.perf_counter()
    """Render the archaeological plate.

    If `fig` is provided, draw into that existing matplotlib figure (skipping
    file save). Otherwise create a new figure and write PNG + SVG to disk.
    The `fig` parameter is for the GUI's live preview path.

    `flip_rim`: if True, flip the entire plate geometry vertically (y → -y)
    before drawing. Use when the auto-orient ended up with the rim at the
    bottom of the section silhouette and you want it visually at the top.

    `rim_incl_deg`: rotates the section silhouette and right profile line
    mirror-symmetrically around their respective rim points (where each
    half meets the y=0 rim baseline). Drawing-only — does not modify any
    recorded measurement (rim Ø, eversion, height stay as computed).
    Range conventionally ±30°. Positive values tilt walls outward (rim
    appears more open); negative values tilt walls inward (rim more closed).
    Used to incorporate archaeological judgment when the geometric fit
    differs from the published convention for a vessel type.

    `view_rot_deg`: nudges the orthographic view direction by this offset
    around the symmetry axis. Range conventionally ±15°. Default 0 uses
    the auto-computed angular center of the preserved arc. Positive values
    rotate the view counterclockwise (looking down the axis from above);
    negative values rotate it clockwise. Use this slider to manually
    correct the rendered sherd image when the auto-detected view is
    slightly off-center (which can happen when the preserved arc is
    asymmetric or contains features that bias the angular mean).

    `unroll_mode`: if True, render the sherd image as a CYLINDRICAL UNROLL
    (rolled-out projection) instead of orthographic. Each vertex's
    horizontal position becomes its arc length around the symmetry axis
    (radius × angle), so the curved cylindrical surface is laid flat. This
    matches the traditional archaeological convention for showing painted
    decoration: the figure appears flat and undistorted regardless of how
    curved the actual sherd is. The angular center of the unrolled view is
    determined by the same `out_dir` used for the orthographic mode (i.e.,
    angles are measured relative to the auto-detected or user-rotated
    front-facing direction). Best for cylindrical/conical vessels where
    the rim radius is reasonably uniform with height; bowls with strongly
    curved profiles will distort somewhat.

    `user_oriented`: if True, the user has explicitly set the mesh
    orientation via the Orient & Scale dialog (rim at +Y). Skip the
    auto-flip heuristic in that case — trust the user's orientation
    rather than re-deriving it from boundary smoothness, which sometimes
    flips ollas and other shapes incorrectly.
    """
    """
    Archaeological plate following the SA06/SA11/SA20/SA23 convention:
      - LEFT (negative x): filled black section silhouette positioned so the
        section's outer wall sits at -R_rim (the actual rim radius)
      - RIGHT (positive x): exterior profile line only, mirrored at +R_rim
      - Both halves connected at the rim with a horizontal line at h=0
      - ⊕ diameter symbol next to the rim line
      - Vertical dot-dashed central axis at x=0
      - Preserved sherd view (frontal, vessel axis vertical) BESIDE the profile
        (in the right column of the plate, not below)
    """
    V = np.asarray(mesh.vertices); F = np.asarray(mesh.faces)
    Nf = np.asarray(mesh.face_normals)
    unit = result.unit
    axis = fit["axis_dir"]; p0 = fit["axis_point"]
    e_a, e_b = fit["e_a"], fit["e_b"]
    R_rim = result.rim_diameter / 2.0
    R_max = result.max_diameter / 2.0

    # ── Compute the RIM CHORD: the straight-line distance between the two
    # endpoint rim points. This is the ground truth for how wide the sherd's
    # rim contact is, and determines how the sherd image should be sized in
    # the plate. ──
    Nv = np.asarray(mesh.vertex_normals)
    try:
        loop = get_boundary_loop(mesh)
        Bp = V[loop]; BpN = Nv[loop]
        Bp_rel = Bp - p0
        Bp_H = Bp_rel @ axis
        Bp_rvec = Bp_rel - np.outer(Bp_H, axis)
        Bp_r = np.linalg.norm(Bp_rvec, axis=1)
        rhat = Bp_rvec / np.maximum(Bp_r[:, None], 1e-6)
        ext_facing = (BpN * rhat).sum(axis=1) > 0.3
        h_max_b = Bp_H.max(); h_min_b = Bp_H.min(); h_rng_b = h_max_b - h_min_b
        rim_zone_strict = ext_facing & (Bp_H > h_max_b - 0.10 * h_rng_b)
        if rim_zone_strict.sum() >= 5:
            rim_pts_3d = Bp[rim_zone_strict]
            # Project to rim plane (2D)
            tmp_v = (np.array([1., 0, 0]) if abs(axis[0]) < 0.9
                      else np.array([0, 1., 0]))
            ea_rim = np.cross(axis, tmp_v); ea_rim /= np.linalg.norm(ea_rim)
            eb_rim = np.cross(axis, ea_rim); eb_rim /= np.linalg.norm(eb_rim)
            rim_pts_2d_chord = np.column_stack([
                (rim_pts_3d - rim_pts_3d.mean(axis=0)) @ ea_rim,
                (rim_pts_3d - rim_pts_3d.mean(axis=0)) @ eb_rim])
            # CHORD = max pairwise distance between any two rim points
            # (this is the maximum extent of the rim arc as a straight line)
            from scipy.spatial.distance import pdist
            pair_dists = pdist(rim_pts_2d_chord)
            rim_chord = float(pair_dists.max()) if len(pair_dists) > 0 else 0.0
        else:
            rim_chord = 0.0
    except Exception:
        rim_chord = 0.0
    # If chord couldn't be computed, fall back to ~half R_rim (a rough guess)
    if rim_chord <= 0:
        rim_chord = R_rim * 0.6

    # The section polygon's coordinates are RADIAL distance from the axis.
    # So the section spans roughly r=[wall_inner, wall_outer] where
    # wall_outer ≈ R_rim. We need to place the section so its outer edge sits
    # at exactly -R_rim on the plate (so it visually meets the rim diameter).
    # ── Auto-flip axis if pointing the WRONG way ──
    # The rest of the function assumes axis points FROM body TOWARD rim, so
    # H_rim = max projection puts the rim plane at y=0 with the body below.
    # When the user supplies a manual axis tweak (θ, φ), the resulting axis
    # vector may point the opposite direction (from rim toward body). That
    # would put the rim plane at the MIN projection, and the entire plot
    # ── BOUNDARY-SMOOTHNESS-BASED AUTO-FLIP ──
    # The rim of a vessel is the smooth boundary edge that the potter
    # finished; broken edges are jagged from fracture. So: for both axis
    # directions (current and flipped), find the boundary points near
    # the "top" end of the projection range, measure how smoothly they
    # form a circular arc, and pick the direction where the top is
    # smoother. This works for cups, jars, ollas, plates, kraters,
    # bowls — any vessel where the rim is finished and breaks are jagged.
    # Falls back to the radial-spread heuristic if boundary loop is bad.
    Vrel = V - p0
    proj = Vrel @ axis
    perp_sq = np.einsum('ij,ij->i', Vrel, Vrel) - proj**2
    perp = np.sqrt(np.clip(perp_sq, 0, None))
    pmin, pmax = proj.min(), proj.max()
    pspan = pmax - pmin

    should_flip = False
    flip_decided_by = "skipped"

    if user_oriented:
        # User has explicitly oriented the mesh in the Orient & Scale
        # dialog with rim at +Y. The fitted symmetry axis may point in
        # either direction along the +Y / -Y line. Just check: does the
        # current axis point toward +Y (rim) or -Y (away from rim)? If
        # it points to -Y, flip it. This is unambiguous since +Y was
        # explicitly set up as "rim direction" by the user.
        # axis is a 3-vector; check its Y component sign.
        if axis[1] < 0:
            should_flip = True
            flip_decided_by = (
                f"user_oriented (+Y is rim; axis Y-component {axis[1]:.3f} "
                f"is negative -> flip)")
        else:
            flip_decided_by = (
                f"user_oriented (+Y is rim; axis Y-component {axis[1]:.3f} "
                f"is positive -> no flip)")
    elif pspan > 0.1:
        try:
            loop_idx = get_boundary_loop(mesh)
        except Exception:
            loop_idx = []

        if len(loop_idx) > 30:
            # We have a usable boundary loop. Project boundary points onto
            # axis and split into two groups: those near the +end and those
            # near the −end of the axis range.
            B = V[loop_idx]
            B_proj = (B - p0) @ axis
            B_perp = np.sqrt(
                np.einsum('ij,ij->i', B - p0, B - p0) - B_proj**2)
            band = 0.20 * pspan
            top_mask = B_proj > pmax - band
            bot_mask = B_proj < pmin + band

            def _boundary_smoothness(B_subset, B_proj_subset, B_perp_subset):
                """Score boundary smoothness for the points in this band.
                A smooth rim has points that lie on a near-perfect circle
                with low residual variance. A jagged break has high
                residual variance (points zigzag in/out).
                Returns: (smoothness_score, n_points). Higher is smoother."""
                if len(B_subset) < 8:
                    return -np.inf, 0
                # The "expected" radius is the median perpendicular distance
                # from axis. Compute residuals from that.
                med_r = float(np.median(B_perp_subset))
                if med_r < 1e-3:
                    return -np.inf, len(B_subset)
                # MAD-based scale of perpendicular variation, normalized
                # by median radius. Lower MAD/r = smoother.
                mad = float(np.median(np.abs(B_perp_subset - med_r)))
                rel_variation = mad / med_r
                # Smoothness score: higher = smoother
                # (negate so smaller variation gives higher score)
                return -rel_variation, len(B_subset)

            top_score, top_n = _boundary_smoothness(
                B[top_mask], B_proj[top_mask], B_perp[top_mask])
            bot_score, bot_n = _boundary_smoothness(
                B[bot_mask], B_proj[bot_mask], B_perp[bot_mask])

            if top_n >= 8 and bot_n >= 8:
                # Decision: rim is the END with HIGHER smoothness score.
                # If that's the bottom, we need to flip.
                # Require a meaningful margin (5% relative) to avoid
                # noise-driven flipping.
                if bot_score > top_score + 0.05:
                    should_flip = True
                    flip_decided_by = (
                        f"smoothness (top {-top_score:.3f} vs "
                        f"bot {-bot_score:.3f}, lower=smoother -> "
                        f"bottom is rim)")
                else:
                    flip_decided_by = (
                        f"smoothness (top {-top_score:.3f} vs "
                        f"bot {-bot_score:.3f}, top is rim)")

        if flip_decided_by == "skipped":
            # Boundary loop unavailable or too small — fall back to
            # the radial-spread heuristic (less reliable but better than
            # nothing).
            band_v = 0.10 * pspan
            near_max = proj > pmax - band_v
            near_min = proj < pmin + band_v
            if near_max.sum() > 5 and near_min.sum() > 5:
                spread_max = float(np.median(perp[near_max]))
                spread_min = float(np.median(perp[near_min]))
                if spread_min > spread_max * 1.10:
                    should_flip = True
                    flip_decided_by = (
                        f"spread fallback (top {spread_max:.2f} vs "
                        f"bot {spread_min:.2f})")
                else:
                    flip_decided_by = (
                        f"spread fallback (top {spread_max:.2f} vs "
                        f"bot {spread_min:.2f}, top wider -> no flip)")

    if should_flip:
        axis = -axis
        section_poly = section_poly.copy()
        section_poly[:, 1] = -section_poly[:, 1]
        _profile_h_flipped = -np.asarray(fit["profile_h"])
    else:
        _profile_h_flipped = None
    # Diagnostic — visible to GUI via captured stdout
    print(f"[auto-flip] decision: flip={should_flip} ({flip_decided_by})")

    # ── USER OVERRIDE: flip_rim ──
    # The user can force an additional flip via the GUI checkbox. This
    # operates on top of the auto-flip decision: if auto-flip applied,
    # flip_rim un-applies it; if auto-flip didn't, flip_rim applies the
    # opposite. Either way, the user gets the geometric flip they want.
    # Implementation: invert axis and negate section_poly+profile_h, same
    # mechanism as auto-flip, so all downstream rendering is naturally
    # in the flipped frame (rim baseline correctly attached to silhouette).
    if flip_rim:
        axis = -axis
        section_poly = section_poly.copy()
        section_poly[:, 1] = -section_poly[:, 1]
        if _profile_h_flipped is not None:
            _profile_h_flipped = -_profile_h_flipped
        else:
            _profile_h_flipped = -np.asarray(fit["profile_h"])
        print(f"[flip-rim] user override applied")

    H_rim = ((V - p0) @ axis).max()
    sect = section_poly.copy()
    sect[:, 1] -= H_rim  # so rim plane = 0
    # ── Critical: realign the section's outer edge to the rim radius ──
    # Find the maximum radius in the section near the top (rim level).
    # That should equal R_rim. If it doesn't, scale the section.
    rim_zone = sect[:, 1] > sect[:, 1].max() - 2  # top 2 cm
    if rim_zone.sum() > 0:
        section_outer_at_rim = sect[rim_zone, 0].max()
        if section_outer_at_rim > 0.1:
            # nothing to shift, but record for reference
            pass

    # Use the flipped profile_h if axis was auto-flipped, else original
    if _profile_h_flipped is not None:
        prof_h = _profile_h_flipped - H_rim
    else:
        prof_h = fit["profile_h"] - H_rim
    prof_r = fit["profile_r"]

    # ── Apply rim inclination (drawing-only) ──
    # Tilt the wall portion below the rim, leaving the rim itself flat.
    # The rim band (top ~10% of the silhouette height) is preserved
    # untouched so the horizontal rim line at y=0 continues to connect
    # both rim corners cleanly. Below the rim band, points rotate around
    # a pivot at the rim/wall transition. To avoid a visible kink at the
    # transition, the rotation angle ramps from 0 (at the rim band) to
    # the full angle (below a transition zone of ~1cm), so the wall
    # smoothly fairs into the rim rather than bending sharply.
    if abs(rim_incl_deg) > 0.01:
        incl_rad = np.radians(rim_incl_deg)
        # Section silhouette
        if len(sect) > 0:
            top_y = float(sect[:, 1].max())
            bot_y = float(sect[:, 1].min())
            h_total = top_y - bot_y
            # Rim band: top ~10% kept fully unrotated. Below that, the
            # rotation ramps up gradually. The transition zone spans 30%
            # of total height (was 10%) — wider zone = gentler curve =
            # less visible kink at any single point.
            rim_band_thickness = max(0.3, 0.08 * h_total)
            transition_zone = max(1.5, 0.35 * h_total)
            rim_bottom_y = top_y - rim_band_thickness
            # Pivot x: outer rim corner = max x in rim band
            rim_band_pts = sect[sect[:, 1] >= rim_bottom_y]
            if len(rim_band_pts) > 0:
                pivot_x = float(rim_band_pts[:, 0].max())
            else:
                pivot_x = float(sect[:, 0].max())
            pivot_y = rim_bottom_y
            # ── Per-point rotation angle (ramp) ──
            # For each silhouette point, compute a rotation factor in [0, 1]:
            #   factor = 0 for points above the rim/wall pivot (no rotation)
            #   factor = 1 for points more than transition_zone below pivot
            #   linear ramp in between
            depth_below_pivot = pivot_y - sect[:, 1]  # > 0 = below pivot
            ramp = np.clip(depth_below_pivot / transition_zone, 0.0, 1.0)
            angles = ramp * incl_rad
            # Apply per-point rotation around (pivot_x, pivot_y). Note:
            # for points where ramp=0 (the rim band), rotation is identity,
            # so they stay exactly in place. For points below the
            # transition zone, full rotation is applied.
            sx = sect[:, 0] - pivot_x
            sy = sect[:, 1] - pivot_y
            cos_a = np.cos(angles); sin_a = np.sin(angles)
            sect = sect.copy()
            sect[:, 0] = cos_a * sx - sin_a * sy + pivot_x
            sect[:, 1] = sin_a * sx + cos_a * sy + pivot_y
        # Right profile: same ramped rotation logic
        if len(prof_h) > 0:
            top_h = float(prof_h.max())
            bot_h = float(prof_h.min())
            h_range_p = top_h - bot_h
            rim_band_thickness_p = max(0.3, 0.08 * h_range_p)
            transition_zone_p = max(1.5, 0.35 * h_range_p)
            rim_bottom_h = top_h - rim_band_thickness_p
            in_rim_band = prof_h >= rim_bottom_h
            if in_rim_band.any():
                piv_r = float(prof_r[in_rim_band].max())
            else:
                piv_r = float(prof_r[np.argmax(prof_h)])
            piv_h = rim_bottom_h
            depth_below_p = piv_h - prof_h
            ramp_p = np.clip(depth_below_p / transition_zone_p, 0.0, 1.0)
            angles_p = ramp_p * (-incl_rad)  # mirror sign for right side
            cos_p = np.cos(angles_p); sin_p = np.sin(angles_p)
            px = prof_r - piv_r
            py = prof_h - piv_h
            prof_r = prof_r.copy()
            prof_h = prof_h.copy()
            prof_r[:] = cos_p * px - sin_p * py + piv_r
            prof_h[:] = sin_p * px + cos_p * py + piv_h

    # ── Layout: ONE main drawing + small rim plan inset ──
    # Per SA20/SA23 convention: section silhouette LEFT, preserved sherd image
    # INTEGRATED INTO THE RIGHT HALF of the same drawing (not a separate panel),
    # both at the same scale, both ending at the rim line.
    if fig is None:
        fig = plt.figure(figsize=(11, 14))
        save_to_disk = True
    else:
        # Caller supplied a figure (GUI live preview path). Clear it first.
        fig.clear()
        # Ensure consistent size for layout calculations
        save_to_disk = False
    gs = fig.add_gridspec(3, 3,
                           width_ratios=[3.0, 0.05, 1.0],
                           height_ratios=[1.0, 4.0, 1.0],
                           hspace=0.05, wspace=0.05)
    ax = fig.add_subplot(gs[:, 0])     # main drawing — full height of left side
    _stage('pre_a')   # checkpoint: figure axes created
    ax_rim = fig.add_subplot(gs[2, 2]) # rim plan — small, bottom right corner

    # ── Keep section at TRUE cm coordinates (no horizontal scaling) ──
    # The section is a wall slice at one azimuth and represents the actual
    # local geometry. We do NOT stretch it to make it visually meet R_rim
    # because that would misrepresent the sherd's true physical size.
    # When the sherd's natural outer-rim is less than R_rim (e.g. partial
    # arc of a large vessel), the visible gap between the section's outer
    # edge and ±R_rim is archaeologically informative — it shows how much
    # of the rim is missing.
    sect_scaled = sect.copy()
    scale_factor = 1.0
    h_range = float(sect[:, 1].max() - sect[:, 1].min())

    # ── LEFT HALF: filled section silhouette (black) ──
    # Mirror to negative x and shift so the section's outer rim point sits
    # at -R_rim (the rim line endpoint). This places the section physically
    # at the rim's left edge.
    section_outer_at_rim_now = 0.0
    rim_band_now = sect_scaled[sect_scaled[:, 1] >
                                sect_scaled[:, 1].max() - max(0.3, 0.10 * h_range)]
    if len(rim_band_now) > 0:
        section_outer_at_rim_now = float(rim_band_now[:, 0].max())
    sect_x_shift = section_outer_at_rim_now - R_rim  # shift to put outer at -R_rim
    sect_left = np.column_stack([-(sect_scaled[:, 0] - sect_x_shift),
                                  sect_scaled[:, 1]])
    ax.add_patch(plt.Polygon(sect_left, closed=True,
                              facecolor="black", edgecolor="black", lw=0.8))

    # ── RIGHT SIDE OF FIGURE ──
    # Layout per user's sketch:
    #   • Section silhouette: LEFT, outer edge at x = -R_rim
    #   • Central axis (dot-dashed vertical): at x = 0
    #   • Preserved sherd image: middle-right, at NATURAL projected size, with
    #     its rim edge clipped horizontally at y=0
    #   • Right profile line: at the FAR RIGHT, traces the outer wall of the
    #     scaled section mirrored — its top sits at x=+R_rim
    #   • Rim line: ONE horizontal straight line at y=0 from -R_rim to +R_rim,
    #     connecting both halves graphically

    # ── Project mesh to 2D with vessel axis = up ──
    # We want to see the EXTERIOR of the sherd (the outward-facing side).
    # The exterior surface has normals pointing radially OUTWARD from the
    # vessel axis. To view that side, we look at the sherd FROM OUTSIDE,
    # i.e., the camera direction (viewdir) points TOWARD the axis.
    # Equivalently, we look from the +out_dir direction toward the axis,
    # which means viewdir = -out_dir (looking inward toward the axis from
    # outside the vessel).
    #
    # However: by the convention `Nf @ -viewdir > 0` for "front-facing",
    # we need -viewdir to align with the OUTWARD direction of the surface.
    # For an exterior surface, outward normal aligns with +out_dir (radially
    # outward). So we want -viewdir = +out_dir, i.e., viewdir = -out_dir.
    # This shows the exterior. (If this still shows interior, flip the sign.)
    # ── Compute out_dir as the angular center of the preserved RIM arc ──
    # The previous code used the mesh centroid, which biases toward the
    # side with more material and produces oblique views on asymmetric
    # sherds. The correct archaeological convention (per traditional
    # ceramic typology drawings) is to look at the sherd in true
    # orthographic projection along the angular center of the preserved
    # rim. This makes the rim run perfectly horizontal across the top of
    # the rendered image and yields a non-distorted front view.
    #
    # Two data sources tried in order:
    #   1. Boundary-loop vertices in the rim band (works for sherds with
    #      open break edges — most archaeological 3D scans).
    #   2. Mesh-vertex points in a thin slab near the rim plane (fallback
    #      for watertight/closed meshes that have NO boundary edges, which
    #      happens with photogrammetry workflows that auto-fill holes).
    up_unit = axis / np.linalg.norm(axis)
    out_dir = None

    def _angular_center_from_points(pts_3d):
        """Given 3D points, project to rim plane and return the circular
        mean angle in axis-relative coords."""
        if len(pts_3d) < 3:
            return None
        perp = (pts_3d - p0) - np.outer((pts_3d - p0) @ axis, axis)
        a = perp @ e_a
        b = perp @ e_b
        # Filter out points too close to axis — unreliable angles
        radii = np.sqrt(a*a + b*b)
        if radii.max() < 1e-3:
            return None
        ok = radii > 0.1 * float(radii.max())
        if ok.sum() < 3:
            return None
        a = a[ok]; b = b[ok]
        angles = np.arctan2(b, a)
        cos_sum = float(np.sum(np.cos(angles)))
        sin_sum = float(np.sum(np.sin(angles)))
        if abs(cos_sum) + abs(sin_sum) < 1e-3:
            return None
        return float(np.arctan2(sin_sum, cos_sum))

    # Method 1: boundary points in rim band
    try:
        loop_idx = get_boundary_loop(mesh)
        if len(loop_idx) > 10:
            B = V[loop_idx]
            B_proj = (B - p0) @ axis
            B_max = float(B_proj.max())
            B_min = float(B_proj.min())
            B_range = B_max - B_min
            rim_mask = B_proj > B_max - 0.15 * B_range
            if rim_mask.sum() >= 3:
                mean_angle = _angular_center_from_points(B[rim_mask])
                if mean_angle is not None:
                    out_dir = (np.cos(mean_angle) * e_a
                                + np.sin(mean_angle) * e_b)
    except Exception:
        pass

    # Method 2: slab-based fallback for watertight meshes.
    # On photogrammetry-closed meshes (no boundary edges) we can't use
    # boundary points. Instead, take a slab of mesh near the rim plane
    # and filter to EXTERIOR faces only — those whose face normal points
    # outward from the symmetry axis. Interior faces (the closed cap) and
    # edge-on faces (the rim itself, broken edge) get rejected. The
    # angular distribution of exterior face centroids reveals where the
    # preserved arc actually is.
    if out_dir is None:
        try:
            F_arr = np.asarray(mesh.faces)
            if len(F_arr) > 0:
                # Compute face centroids and their projection onto the axis
                Fc = V[F_arr].mean(axis=1)
                Fc_proj = (Fc - p0) @ axis
                F_max = float(Fc_proj.max())
                F_min = float(Fc_proj.min())
                F_range = F_max - F_min
                # Top 10% slab — wider than method-1 to ensure enough faces
                slab_mask = Fc_proj > F_max - 0.10 * F_range
                if slab_mask.sum() >= 30:
                    # Outward radial direction at each face
                    Fc_perp = (Fc - p0) - np.outer((Fc - p0) @ axis, axis)
                    Fc_radii = np.linalg.norm(Fc_perp, axis=1)
                    safe = Fc_radii > 1e-6
                    Fc_outward = np.zeros_like(Fc_perp)
                    Fc_outward[safe] = Fc_perp[safe] / Fc_radii[safe, None]
                    # Face normals
                    Nf = np.asarray(mesh.face_normals)
                    # Filter: exterior face = normal aligned with outward dir
                    out_dot = np.einsum('ij,ij->i', Nf, Fc_outward)
                    exterior_mask = slab_mask & (out_dot > 0.3) & safe
                    if exterior_mask.sum() >= 10:
                        mean_angle = _angular_center_from_points(
                            Fc[exterior_mask])
                        if mean_angle is not None:
                            out_dir = (np.cos(mean_angle) * e_a
                                        + np.sin(mean_angle) * e_b)
        except Exception:
            pass

    # Final fallback: mesh centroid direction
    if out_dir is None:
        centroid = V.mean(axis=0)
        centroid_rel = centroid - p0
        centroid_perp = centroid_rel - (centroid_rel @ axis) * axis
        perp_norm = np.linalg.norm(centroid_perp)
        out_dir = centroid_perp / perp_norm if perp_norm > 0.01 else e_a

    # Apply user's manual view rotation around the symmetry axis. This
    # rotates the camera direction by `view_rot_deg` around `axis`, which
    # in turn rotates `out_dir` by the same angle in the e_a/e_b plane.
    # Used to fine-tune the auto-detected angular center when the
    # preserved arc is asymmetric.
    if abs(view_rot_deg) > 1e-6:
        rot_rad = np.radians(view_rot_deg)
        # Decompose current out_dir in the e_a/e_b basis
        a0 = float(out_dir @ e_a)
        b0 = float(out_dir @ e_b)
        cos_r = np.cos(rot_rad); sin_r = np.sin(rot_rad)
        a_new = cos_r * a0 - sin_r * b0
        b_new = sin_r * a0 + cos_r * b0
        out_dir = a_new * e_a + b_new * e_b
        # Renormalize defensively
        nrm = np.linalg.norm(out_dir)
        if nrm > 1e-9:
            out_dir = out_dir / nrm

    viewdir = -out_dir
    right_unit = np.cross(viewdir, up_unit); right_unit /= np.linalg.norm(right_unit)

    V_rel = V - p0
    V_y = (V_rel @ up_unit) - H_rim   # rim plane at y=0
    _stage('pre_b')   # checkpoint: V_x_raw / V_y projection done

    if unroll_mode:
        # ── ISORADIAL CYLINDRICAL UNROLL ──
        # Each vertex's unrolled x is its LOCAL arc length around the
        # symmetry axis: x = θ · r(z), where r(z) is the perpendicular
        # distance of THAT vertex from the axis (not a single rim radius).
        # Correct for cylinders, conical sections, and any surface of
        # revolution: arc lengths along any horizontal circle are
        # preserved exactly. Replaces an earlier version that used
        # rim_R as a constant — which oversized the body of any
        # flaring vessel by the rim/body ratio (visible as the unroll
        # being "too large" relative to the original sherd).
        try:
            rim_R = float(result.rim_diameter) / 2.0
        except Exception:
            rim_R = 1.0
        if rim_R <= 0:
            rim_R = 1.0
        # In-plane components in the e_a/e_b basis
        V_perp = V_rel - np.outer(V_rel @ axis, axis)
        a_comp = V_perp @ e_a
        b_comp = V_perp @ e_b
        # Local radius at every vertex
        local_R = np.sqrt(a_comp * a_comp + b_comp * b_comp)
        # Fall back to rim radius for any vertex right on the axis (avoids 0)
        local_R = np.where(local_R < 1e-6, rim_R, local_R)
        # Angle of out_dir in the same basis
        out_a = float(out_dir @ e_a)
        out_b = float(out_dir @ e_b)
        out_angle = np.arctan2(out_b, out_a)
        # Angle of each vertex
        v_angles = np.arctan2(b_comp, a_comp)
        # Signed angular distance from out_dir, normalized to [-π, π]
        d_angle = np.mod(v_angles - out_angle + np.pi, 2 * np.pi) - np.pi
        # Arc length = angle × LOCAL radius (per vertex)
        V_x_raw = d_angle * local_R
    else:
        V_x_raw = V_rel @ right_unit

    # Mirror so the sherd's centroid is on +x side
    if V_x_raw.mean() < 0:
        V_x_raw = -V_x_raw
        right_unit = -right_unit
        viewdir = -viewdir

    # ── Verify we're showing the exterior, not the interior ──
    # Count front-facing faces under each candidate viewdir; pick the
    # direction that shows MORE outward-facing (exterior) surface area.
    Nv_face = np.asarray(mesh.face_normals)
    out_normal_dot = Nv_face @ out_dir  # > 0 means face is exterior-pointing
    is_exterior_face = out_normal_dot > 0.1

    # Front-facing under current viewdir (current setup)
    front_curr = Nv_face @ (-viewdir) > 0.1
    # Score: how much of the visible surface is genuinely exterior
    score_curr = (is_exterior_face & front_curr).sum() / max(front_curr.sum(), 1)

    # Try the OPPOSITE viewdir
    front_opp = Nv_face @ viewdir > 0.1
    score_opp = (is_exterior_face & front_opp).sum() / max(front_opp.sum(), 1)

    if score_opp > score_curr + 0.05:
        # The opposite view direction shows more exterior surface — flip
        viewdir = -viewdir
        right_unit = -right_unit
        V_x_raw = -V_x_raw

    # ── USER OVERRIDE: explicit flip_view toggle ──
    # When the auto-detection picks the wrong side (common for sherds whose
    # interior and exterior have similar projected areas, e.g. thin-walled
    # rim fragments), the user can force a flip via the GUI checkbox.
    if flip_view:
        viewdir = -viewdir
        right_unit = -right_unit
        V_x_raw = -V_x_raw

    # ── Clip each triangle at y=0 ──
    # The clipping side depends on which direction the body extends from
    # the rim plane. Normally the body is at y < 0 (rim at top). But when
    # flip_rim is active and not cancelled by auto-flip, V_y orientation
    # may invert — body ends up at y > 0. Detect by sign of mean V_y
    # below the rim band: if mean V_y of vertices NOT in the rim band is
    # negative, body is below (normal); if positive, body is above
    # (flipped). Clip accordingly.
    Nv_face = np.asarray(mesh.face_normals)
    # ── Parameterized lighting ──
    # The light vector is built in a camera-relative frame:
    #   - light_az_deg rotates the light around the symmetry axis (up_unit)
    #     starting from -viewdir (i.e., pointing TOWARD the sherd from the
    #     camera). 0° = light from camera. ±90° = side-lit (raking light,
    #     which dramatically reveals surface relief). ±180° = back-lit.
    #   - light_el_deg tilts the light up (positive) or down (negative)
    #     around the camera's right axis. Default 20° gives a slightly
    #     elevated three-quarter light.
    # The contrast multiplier scales the shading range — higher contrast
    # = more dramatic shadow/highlight separation, with a gamma curve
    # applied for visual punch.
    cam_back = -viewdir  # toward the sherd from the camera
    az_rad = np.radians(light_az_deg)
    el_rad = np.radians(light_el_deg)
    # Rotate cam_back around up_unit by az_rad
    cos_a, sin_a = np.cos(az_rad), np.sin(az_rad)
    light_horiz = (cos_a * cam_back
                    + sin_a * np.cross(up_unit, cam_back))
    # Tilt by el_rad toward up_unit
    light = (np.cos(el_rad) * light_horiz
              + np.sin(el_rad) * up_unit)
    n = np.linalg.norm(light)
    if n > 1e-9:
        light = light / n
    else:
        light = -viewdir
    # Compute base shading: dot product of face normal with light direction
    raw_shade = np.clip(Nv_face @ light, 0, 1)
    # Apply contrast: gamma-style curve. contrast=1.0 reproduces the
    # original (0.45-1.0 range, modest shading); contrast > 1 makes
    # shadows deeper and highlights more punchy; contrast < 1 flattens.
    contrast_safe = max(0.1, float(contrast))
    # Normalize: at contrast=1.0, output range is [0.45, 1.0] (matches old)
    # at contrast=2.0, output range is roughly [0.15, 1.0] (more dramatic)
    # at contrast=0.5, output range is roughly [0.65, 1.0] (flatter)
    gamma = 1.0 / contrast_safe
    raw_shade_g = np.power(raw_shade, gamma)
    # Map [0, 1] → [low, 1] where low is contrast-dependent
    low = max(0.05, 0.45 / contrast_safe)
    shade_all = raw_shade_g * (1.0 - low) + low
    # ── Vertex/texture colour rendering (optional) ──
    # If the mesh has per-vertex colours OR a texture+UVs AND
    # use_vertex_colors is True, use them; otherwise fall back to
    # grayscale shading. The helper handles all three real cases
    # (vertex colours, texture image, PBR baseColorTexture); the
    # earlier code only handled the first one and silently failed on
    # textured photogrammetry meshes.
    has_color = False
    face_base_colors = None
    if use_vertex_colors:
        face_base_colors = _sample_face_colors_from_mesh(mesh, V)
        if face_base_colors is not None and face_base_colors.std() > 0.01:
            has_color = True
    _stage('pre_c')   # checkpoint: colour sampling done
    if unroll_mode:
        # In unroll mode, "front-facing" = the exterior surface of the
        # vessel (faces whose normals point radially outward), regardless
        # of where the camera would be. This shows the whole preserved
        # arc unrolled flat. Faces of the auto-filled interior cap
        # (common in watertight photogrammetry meshes) get filtered out.
        # Tight threshold (0.6) to reject cap edges whose normals only
        # mildly tilt outward.
        F_arr = np.asarray(mesh.faces)
        Fc_all = V[F_arr].mean(axis=1)
        Fc_perp = (Fc_all - p0) - np.outer((Fc_all - p0) @ axis, axis)
        Fc_radii = np.linalg.norm(Fc_perp, axis=1)
        safe = Fc_radii > 1e-6
        Fc_outward = np.zeros_like(Fc_perp)
        Fc_outward[safe] = Fc_perp[safe] / Fc_radii[safe, None]
        out_dot_face = np.einsum('ij,ij->i', Nv_face, Fc_outward)
        front_all = (out_dot_face > 0.6) & safe
    else:
        front_all = Nv_face @ (-viewdir) > -0.2

    # Determine body-side direction
    body_below = float(V_y.mean()) < 0
    if not body_below:
        # Negate V_y so the body lands at y < 0 for the clipping logic.
        # Section/profile coords are computed independently and don't need
        # this — they were already shifted by H_rim on the same flipped
        # axis. Only the sherd-image clipping path depends on V_y sign.
        V_y = -V_y

    # ── VECTORISED triangle-to-polygon conversion ──
    # The previous version of this code looped over every triangle in
    # Python, doing numpy scalar indexing inside the loop. On a 500k-face
    # mesh that takes ~5 s per slider tick — entirely independent of
    # whether matplotlib or the GPU path renders the polygons afterward.
    # Vectorising the bulk case (triangles fully below the rim) takes the
    # 5 s down to ~100 ms; the rare per-triangle clipping at the rim line
    # is still done in Python but only runs for the small subset of
    # triangles that actually straddle y=0.
    _stage('clip_start')
    tri_depth = (V @ (-viewdir))[F].mean(axis=1)
    order = np.argsort(tri_depth)

    F_x = V_x_raw[F]               # (Nf, 3)
    F_y = V_y[F]                   # (Nf, 3)
    y_max = F_y.max(axis=1); y_min = F_y.min(axis=1)
    x_span = F_x.max(axis=1) - F_x.min(axis=1)

    # Discontinuity filter for unroll mode (otherwise no constraint)
    if unroll_mode:
        max_tri_x_span = float(local_R.max()) * (np.pi / 2)
        not_disc = x_span <= max_tri_x_span
    else:
        not_disc = np.ones(len(F), dtype=bool)

    # Three classes:
    #   below   — fully at or below rim, keep as-is
    #   straddle — crosses y=0, needs per-triangle clipping
    #   above   — fully above rim, drop
    valid = front_all & not_disc
    mask_below    = valid & (y_max <= 0.0)
    mask_straddle = valid & (y_min < 0.0) & (y_max > 0.0)

    # Restrict the depth-sort order to valid triangles in either class
    keep_mask = mask_below | mask_straddle
    order_kept = order[keep_mask[order]]   # depth-sorted, only valid tris

    # ── BULK CASE — vectorised ──
    # Build per-triangle (3,2) polys for every "fully below" triangle in
    # one shot, then convert to a list of arrays at the very end (matplotlib's
    # PolyCollection wants a sequence). The list construction itself is in C.
    below_in_order = order_kept[mask_below[order_kept]]
    if len(below_in_order):
        xy_below = np.empty((len(below_in_order), 3, 2), dtype=np.float64)
        xy_below[:, :, 0] = F_x[below_in_order]
        xy_below[:, :, 1] = F_y[below_in_order]
        if has_color:
            base = face_base_colors[below_in_order]                    # (N,3)
            sh   = shade_all[below_in_order][:, None]                  # (N,1)
            cols_below = base * sh
        else:
            sh = shade_all[below_in_order]
            cols_below = np.stack([sh, sh, sh * 0.95], axis=1)
        # IMPORTANT: list(xy_below) creates a list of *views* into xy_below,
        # not copies. So in-place modifications to xy_below propagate to
        # polys_below entries automatically. Downstream code that does
        # `for p in clipped_polys: p[:, 0] *= s` works correctly, but is
        # 100× slower than `xy_below[:, :, 0] *= s`. The vectorised post-
        # clipping section below uses xy_below directly and falls back
        # to the Python list only at PolyCollection/GPU-rasteriser time.
        polys_below = list(xy_below)
        colors_below = cols_below.tolist()
    else:
        xy_below = None
        cols_below = None
        polys_below = []; colors_below = []

    # ── STRADDLING CASE — Python loop, but tiny set ──
    # Only triangles that actually cross y=0. On a typical sherd this is
    # a few hundred triangles out of 500k, so a Python loop is fine.
    polys_straddle = []; colors_straddle = []
    straddle_in_order = order_kept[mask_straddle[order_kept]]
    if len(straddle_in_order):
        for i in straddle_in_order:
            tri_x = F_x[i]; tri_y = F_y[i]
            poly_pts = []
            for j in range(3):
                ax_j, ay_j = tri_x[j], tri_y[j]
                bx_j, by_j = tri_x[(j+1) % 3], tri_y[(j+1) % 3]
                if ay_j <= 0:
                    poly_pts.append((ax_j, ay_j))
                if (ay_j < 0) != (by_j < 0):
                    if abs(by_j - ay_j) > 1e-9:
                        t = (0 - ay_j) / (by_j - ay_j)
                        poly_pts.append((ax_j + t*(bx_j - ax_j), 0.0))
            if len(poly_pts) >= 3:
                polys_straddle.append(np.array(poly_pts))
                if has_color:
                    base = face_base_colors[i]
                    colors_straddle.append([base[0] * shade_all[i],
                                             base[1] * shade_all[i],
                                             base[2] * shade_all[i]])
                else:
                    colors_straddle.append([shade_all[i], shade_all[i],
                                             shade_all[i] * 0.95])

    # Merge while preserving depth order: bulk and straddle are each in
    # depth order; interleave by reading from the original order_kept.
    # In practice the two passes are already in depth order independently,
    # and a sherd's "straddle" triangles all live at one depth band (the
    # rim), so just concatenating is visually fine. We concatenate
    # below-tris first (back) then straddle-tris (front) which keeps the
    # rim-line clipped polys on top.
    clipped_polys = polys_below + polys_straddle
    clipped_colors = colors_below + colors_straddle
    _stage('rasterise_start')

    # ── Position the sherd image: shift so leftmost edge sits at x=0 ──
    # The projected sherd has its own natural width (chord of preserved arc).
    # Shift it so its leftmost projected point is at x=0 (touching the central
    # axis). Apply the SAME scale_factor as the section so both halves match.
    # The right profile line will be drawn at +R_rim regardless of where the
    # sherd's right edge lands.
    #
    # PERFORMANCE: All these transformations now operate on the bulk ndarray
    # `xy_below` directly (vectorised) plus a small Python loop over the few
    # straddling triangles. Earlier this section was 5+ Python loops over
    # 500k polys = ~3 s; now it's milliseconds.
    if clipped_polys:
        # Helper: assemble all_x / all_y from bulk + straddle without iterating
        # over the bulk in Python.
        def _all_xy():
            parts_x = []; parts_y = []
            if xy_below is not None and len(xy_below):
                parts_x.append(xy_below[:, :, 0].ravel())
                parts_y.append(xy_below[:, :, 1].ravel())
            if polys_straddle:
                for p in polys_straddle:
                    parts_x.append(p[:, 0]); parts_y.append(p[:, 1])
            ax_arr = np.concatenate(parts_x) if parts_x else np.empty(0)
            ay_arr = np.concatenate(parts_y) if parts_y else np.empty(0)
            return ax_arr, ay_arr

        all_x, all_y = _all_xy()

        # Find the sherd image's natural width AT THE RIM LEVEL (top band).
        y_top_band = all_y > all_y.max() - max(0.3, 0.10 * (all_y.max() - all_y.min()))
        if y_top_band.sum() > 5:
            sherd_natural_chord = float(all_x[y_top_band].max() - all_x[y_top_band].min())
        else:
            sherd_natural_chord = float(all_x.max() - all_x.min())

        # Scale x to match the rim chord
        if sherd_natural_chord > 0.1 and rim_chord > 0.1:
            sherd_x_scale = rim_chord / sherd_natural_chord
            if 0.3 < sherd_x_scale < 5.0:
                if xy_below is not None and len(xy_below):
                    xy_below[:, :, 0] *= sherd_x_scale     # vectorised
                for p in polys_straddle:
                    p[:, 0] *= sherd_x_scale
                all_x = all_x * sherd_x_scale

        # ── Position sherd: LEFT-align at x=0 (central axis) ──
        y_top_band = all_y > all_y.max() - max(0.3, 0.10 * (all_y.max() - all_y.min()))
        if y_top_band.sum() > 5:
            sherd_inner_x = float(all_x[y_top_band].min())
        else:
            sherd_inner_x = float(all_x.min())
        x_shift = 0.0 - sherd_inner_x  # so leftmost top is at x=0
        if xy_below is not None and len(xy_below):
            xy_below[:, :, 0] += x_shift                  # vectorised
        for p in polys_straddle:
            p[:, 0] += x_shift
        all_x = all_x + x_shift

        # ── IMAGE ROTATION (drawing-only) ──
        if abs(image_rot_deg) > 1e-3 and clipped_polys:
            rot_rad = np.radians(image_rot_deg)
            cos_r = np.cos(rot_rad); sin_r = np.sin(rot_rad)
            if y_top_band.sum() > 5:
                pivot_x = float(all_x[y_top_band].mean())
            else:
                pivot_x = float(all_x.mean())
            pivot_y = 0.0  # rim plane
            if xy_below is not None and len(xy_below):
                rx = xy_below[:, :, 0] - pivot_x
                ry = xy_below[:, :, 1] - pivot_y
                xy_below[:, :, 0] = cos_r * rx - sin_r * ry + pivot_x
                xy_below[:, :, 1] = sin_r * rx + cos_r * ry + pivot_y
            for p in polys_straddle:
                rx = p[:, 0] - pivot_x; ry = p[:, 1] - pivot_y
                p[:, 0] = cos_r * rx - sin_r * ry + pivot_x
                p[:, 1] = sin_r * rx + cos_r * ry + pivot_y
            all_x, all_y = _all_xy()
            y_top_band = all_y > all_y.max() - max(0.3, 0.10 * (all_y.max() - all_y.min()))

        # ── MANUAL SHERD TWEAK ──
        if (abs(sherd_dx) > 1e-3 or abs(sherd_dy) > 1e-3
                or abs(sherd_scale - 1.0) > 1e-3):
            if y_top_band.sum() > 5:
                pivot_x = float(all_x[y_top_band].mean())
            else:
                pivot_x = float(all_x.mean())
            pivot_y = 0.0  # rim plane
            if xy_below is not None and len(xy_below):
                xy_below[:, :, 0] = (xy_below[:, :, 0] - pivot_x) * sherd_scale + pivot_x + sherd_dx
                xy_below[:, :, 1] = (xy_below[:, :, 1] - pivot_y) * sherd_scale + pivot_y + sherd_dy
            for p in polys_straddle:
                p[:, 0] = (p[:, 0] - pivot_x) * sherd_scale + pivot_x + sherd_dx
                p[:, 1] = (p[:, 1] - pivot_y) * sherd_scale + pivot_y + sherd_dy
            all_x, all_y = _all_xy()

        sherd_left_x = float(all_x.min())
        sherd_right_x = float(all_x.max())
        sherd_bot_y = float(all_y.min())
        sherd_top_y = float(all_y.max())
    else:
        sherd_left_x = 0.0
        sherd_right_x = R_rim
        sherd_bot_y = sect_scaled[:, 1].min()
        sherd_top_y = 0.0

    # Publish the sherd image's bounding box in plate coords so the GUI's
    # stroke layer can anchor user-drawn polylines to the sherd. Strokes
    # are stored in normalized 0..1 sherd-local coordinates and converted
    # back to plate coords on redraw using whichever bbox is current.
    global _LAST_SHERD_BBOX
    _LAST_SHERD_BBOX = (sherd_left_x, sherd_right_x, sherd_bot_y, sherd_top_y)

    if clipped_polys and not hide_sherd:
        # ── GPU rasterisation path (optional) ──
        # When gpu_render=True and PyVista is functional, rasterise the
        # already-shaded polygons through OpenGL and composite via imshow.
        # Falls back silently to matplotlib's PolyCollection on any failure.
        rgba_gpu = None
        _gpu_t0 = _time.perf_counter()
        if gpu_render:
            # Use the cached all_x/all_y from upstream rather than rebuilding
            # via Python loop. (The transformations above already updated
            # them in sync with xy_below.)
            ext = (float(all_x.min()), float(all_x.max()),
                   float(all_y.min()), float(all_y.max()))
            ax_w = ext[1] - ext[0]
            ax_h = ext[3] - ext[2]
            # Pick pixel size based on plate scale; ~120 px per cm matches
            # the rest of the plate's effective resolution.
            ppc = 120.0
            img_w = max(64, int(round(ax_w * ppc)))
            img_h = max(64, int(round(ax_h * ppc)))
            # Cap to keep memory bounded on very large sherds.
            scale = max(1.0, max(img_w, img_h) / 2000.0)
            img_w = int(round(img_w / scale))
            img_h = int(round(img_h / scale))
            rgba_gpu = _rasterize_polys_gpu(
                clipped_polys, clipped_colors, ext, (img_w, img_h),
                alpha=0.85,
                bulk_xy=xy_below,
                bulk_colors=cols_below,
                straddle_polys=polys_straddle,
                straddle_colors=colors_straddle)
            if rgba_gpu is not None:
                ax.imshow(rgba_gpu, extent=ext, origin='upper',
                          interpolation='bilinear', zorder=3)
        _gpu_t1 = _time.perf_counter()
        # Track whether the GPU path actually ran successfully (for diagnostics)
        _LAST_TIMING['gpu_attempted'] = bool(gpu_render)
        _LAST_TIMING['gpu_succeeded'] = (rgba_gpu is not None)
        _LAST_TIMING['gpu_call_ms']   = (_gpu_t1 - _gpu_t0) * 1000

        # ── CPU PolyCollection path (fallback / default) ──
        if rgba_gpu is None:
            _cpu_t0 = _time.perf_counter()
            pc = PolyCollection(clipped_polys, facecolors=clipped_colors,
                                 edgecolors="none", linewidths=0,
                                 alpha=0.85)  # slight transparency so profile shows through
            ax.add_collection(pc)
            _LAST_TIMING['polycoll_ms'] = (_time.perf_counter() - _cpu_t0) * 1000
            _LAST_TIMING['rast_path']   = 'CPU PolyCollection'
        else:
            _LAST_TIMING['rast_path']   = 'GPU OpenGL'

        # ── RIM-FLATTENING TOP FILLER (vectorised) ──
        # The clipped sherd polygons may have their visible top edge below y=0
        # in places, creating a concave dip in the rim line. This happens
        # because the actual sherd edge isn't perfectly planar (everted rims,
        # mesh roughness) and triangles clipped at y=0 can leave small gaps.
        # The archaeological convention is a perfectly straight horizontal
        # rim line, so we add filler polygons that extend each top-region
        # triangle UP to y=0 wherever it falls short.
        #
        # Vectorised version: collect top-band vertices from xy_below in one
        # boolean-mask op rather than iterating 500k polys in Python.
        top_pts_parts = []
        if xy_below is not None and len(xy_below):
            in_top = xy_below[:, :, 1] > -0.05         # (N, 3) bool
            # Only keep rows with ≥2 vertices in the top band (matches
            # original logic). We then flatten down to the top vertices.
            row_keep = in_top.sum(axis=1) >= 2
            if row_keep.any():
                kept_xy = xy_below[row_keep]            # (M, 3, 2)
                kept_in = in_top[row_keep]              # (M, 3) bool
                top_pts_parts.append(kept_xy[kept_in])  # (P, 2)
        for p in polys_straddle:
            top_mask = p[:, 1] > -0.05
            if top_mask.sum() >= 2:
                top_pts_parts.append(p[top_mask])
        all_top_vertices = top_pts_parts

        if all_top_vertices:
            top_pts = np.concatenate(all_top_vertices, axis=0)
            # Sort by x, keep only the highest y at each x cluster
            x_min = float(top_pts[:, 0].min())
            x_max = float(top_pts[:, 0].max())
            n_bins = max(40, min(200, int((x_max - x_min) * 20)))
            bin_edges = np.linspace(x_min, x_max, n_bins + 1)
            top_edge = []
            for i in range(n_bins):
                in_bin = (top_pts[:, 0] >= bin_edges[i]) & (top_pts[:, 0] <= bin_edges[i+1])
                if in_bin.sum() > 0:
                    bin_x = 0.5 * (bin_edges[i] + bin_edges[i+1])
                    bin_y_max = float(top_pts[in_bin, 1].max())
                    top_edge.append((bin_x, bin_y_max))
            if len(top_edge) >= 2:
                top_edge = np.array(top_edge)
                # Fill the gap between top_edge and y=0 with a wide polygon
                fill_x = np.concatenate([top_edge[:, 0], top_edge[:, 0][::-1]])
                fill_y = np.concatenate([top_edge[:, 1], np.zeros(len(top_edge))])
                # Use a representative shade from the sherd
                fill_color = "#f0ede5"  # warm off-white matching the sherd
                ax.fill(fill_x, fill_y, color=fill_color,
                        edgecolor="none", alpha=0.9, zorder=2)

    # ── RIGHT PROFILE LINE: at +R_rim, tracing scaled section's outer wall ──
    # Drawn ON TOP of the sherd image so it remains visible as the canonical
    # right-side outline.
    #
    # IMPORTANT: For OPEN FORMS (bowl/plate), the profile line should NOT
    # extend down to close at the central axis — that would imply the vessel
    # closes to a point at the bottom, which is wrong for an open form.
    # Instead, only draw the profile where the section actually has material.
    # For CLOSED FORMS (jar/krater), the full mirrored outer wall is drawn.
    form_class = getattr(result, 'form_class', 'unknown')
    is_open_form = form_class in ('plate', 'bowl')

    outer_wall = extract_outer_wall(sect_scaled)
    if outer_wall is not None and len(outer_wall) >= 3:
        # Shift the outer wall so its top sits at +R_rim (matching the sherd
        # image's right edge). Both halves should have their outer rim points
        # aligned at ±R_rim.
        outer_wall_shifted = outer_wall.copy()
        outer_wall_shifted[:, 0] += (R_rim - section_outer_at_rim_now)
        if is_open_form:
            r_threshold = R_rim * 0.3
            valid = outer_wall_shifted[:, 0] > r_threshold
            if valid.any():
                outer_wall_truncated = outer_wall_shifted[valid]
                ax.plot(outer_wall_truncated[:, 0], outer_wall_truncated[:, 1],
                        color="black", lw=1.0, zorder=5)
        else:
            ax.plot(outer_wall_shifted[:, 0], outer_wall_shifted[:, 1],
                    color="black", lw=1.0, zorder=5)

    # ── RIM LINE: horizontal straight line at y=0 from -R_rim to +R_rim ──
    # The standard archaeological convention. The line spans the full
    # diameter at the rim plane; the diameter ⊕ symbol on the right and
    # the rim-Ø label up top reinforce the measurement.
    #
    # Earlier versions tried to draw a chord between the sherd's actual
    # rim endpoints. That sounds appealing but the "rim endpoint" of a
    # photogrammetric sherd is noisy — a small chip just below y=0 in
    # the top vertex band can pull the line dramatically downward and
    # produce visibly wrong output. Reverted to the dependable horizontal.
    rim_line_y = 0.0
    ax.plot([-R_rim, R_rim], [rim_line_y, rim_line_y],
             color="black", lw=0.9, zorder=5)

    # ── SA06-style connector wedges: from section's top-outer to ±R_rim ──
    # Since the section is shifted so its outer rim sits exactly at -R_rim,
    # there's only a vertical gap (the everted-rim height) to bridge.
    rim_band = sect_scaled[sect_scaled[:, 1] > sect_scaled[:, 1].max() - 1.5]
    if len(rim_band) > 0:
        sec_top_outer_r = float(rim_band[:, 0].max())
        sec_top_outer_h = float(rim_band[rim_band[:, 0].argmax(), 1])
        # After shifting: section's top-outer is at -R_rim (left) and +R_rim (right)
        # So if sec_top_outer_h < 0 (rim of section below y=0), draw vertical
        # connectors at ±R_rim.
        if sec_top_outer_h < -0.05:
            ax.plot([-R_rim, -R_rim],
                     [sec_top_outer_h, rim_line_y],
                     color="black", lw=0.7)
            ax.plot([R_rim, R_rim],
                     [sec_top_outer_h, rim_line_y],
                     color="black", lw=0.7)

    # ── CENTRAL AXIS (dot-dashed, vertical) ──
    H_bot_drawing = min(sect_scaled[:, 1].min(), sherd_bot_y) - 1
    H_top_drawing = rim_line_y + max(2, R_rim * 0.05)
    ax.plot([0, 0], [H_bot_drawing, H_top_drawing],
             color="black", lw=0.6, dashes=[6, 3, 1, 3])

    # ── ⊕ DIAMETER SYMBOL (right of rim) ──
    circle_r = max(R_rim * 0.04, 0.4)
    th_circ = np.linspace(0, 2*np.pi, 50)
    cx = R_rim + circle_r * 1.8
    ax.plot(cx + circle_r*np.cos(th_circ), rim_line_y + circle_r*np.sin(th_circ),
             color="black", lw=0.6)
    ax.plot([cx - circle_r, cx + circle_r], [rim_line_y, rim_line_y], color="black", lw=0.6)
    ax.plot([cx, cx], [rim_line_y - circle_r, rim_line_y + circle_r], color="black", lw=0.6)

    # ── Rim diameter label ABOVE the rim line ──
    ax.text(0, rim_line_y + circle_r * 3, f"rim Ø {2*R_rim:.1f} {unit}",
             ha="center", va="bottom", fontsize=12, fontweight="bold")

    # ── Determine display radius (max of section and sherd extents) ──
    R_max_section = float(sect_scaled[:, 0].max())
    R_max_display = max(R_max_section, R_rim, sherd_right_x)

    # ── Scale bar (bottom-left of drawing) ──
    sb_len = 5 if R_rim < 15 else 10
    sb_x0 = -R_max_display * 1.2
    sb_y0 = H_bot_drawing + max(1, h_range * 0.03)
    ax.plot([sb_x0, sb_x0 + sb_len], [sb_y0, sb_y0], "k-", lw=2.5)
    ax.text(sb_x0 + sb_len/2, sb_y0 + max(0.5, h_range*0.02),
             f"{sb_len} {unit}", ha="center", fontsize=9)

    # ── Max body diameter annotation (only if shoulder noticeably wider) ──
    if R_max_section > R_rim * 1.05:
        idx_max = int(np.argmax(sect_scaled[:, 0]))
        h_s = sect_scaled[idx_max, 1]
        ax.text(R_max_section + 0.5, h_s,
                f"max Ø {2*R_max_section:.1f} {unit}",
                ha="left", va="center", fontsize=9, color="#888", style="italic")

    # Use R_rim as the dominant x-extent so the rim diameter is shown to scale.
    # Add a small margin on the left for the section if it extends past -R_rim,
    # and a margin on the right large enough for the ⊕ symbol AND for the sherd
    # image if it happens to extend past +R_rim (common for bowls/plates with
    # wide preserved arcs). When rim_incl_deg is nonzero, the section can
    # extend laterally beyond its un-tilted footprint — read the actual
    # silhouette extent from sect_left (the post-mirror, post-tilt coords)
    # rather than approximating from sect_scaled.
    sect_left_min_x = float(sect_left[:, 0].min())  # most-negative x of black silhouette
    section_overhang_left = max(0.0, -sect_left_min_x - R_rim)
    x_pad_left = max(R_rim * 0.15, section_overhang_left + R_rim * 0.1)
    sherd_overhang = max(0.0, sherd_right_x - R_rim)
    # Also check the right profile for overhang past +R_rim
    profile_overhang_right = 0.0
    try:
        # outer_wall_shifted holds the right profile in plot coords
        if 'outer_wall_shifted' in locals() and len(outer_wall_shifted) > 0:
            profile_overhang_right = max(0.0,
                float(outer_wall_shifted[:, 0].max()) - R_rim)
    except Exception:
        pass
    x_pad_right = max(R_rim * 0.25,
                       sherd_overhang + R_rim * 0.1,
                       profile_overhang_right + R_rim * 0.1)
    ax.set_xlim(-R_rim - x_pad_left, R_rim + x_pad_right)
    # ylim: also expand to accommodate inclined section that may extend
    # below sect_scaled.min() in the y direction
    sect_left_min_y = float(sect_left[:, 1].min())
    H_bot_drawing_eff = min(H_bot_drawing, sect_left_min_y - 0.5)
    ax.set_ylim(H_bot_drawing_eff - 1, rim_line_y + max(R_rim*0.1, circle_r*5))
    ax.set_aspect("equal")
    ax.set_title(f"{result.name}", fontsize=13, pad=10)
    ax.set_xlabel(f"radius ({unit})", fontsize=9)
    ax.set_ylabel(f"height below rim ({unit})", fontsize=9)
    for s in ["top", "right"]: ax.spines[s].set_visible(False)

    # ── RIM PLAN (bottom right) ──
    th = np.linspace(0, 2*np.pi, 200)
    ax_rim.plot(R_rim*np.cos(th), R_rim*np.sin(th), "k--", lw=0.7, alpha=0.7,
                 label=f"rim Ø {2*R_rim:.1f}")
    if R_max_display > R_rim * 1.02:
        ax_rim.plot(R_max_display*np.cos(th), R_max_display*np.sin(th), color="gray",
                     lw=0.5, ls=":", alpha=0.8, label=f"max Ø {2*R_max_display:.1f}")
    loop = get_boundary_loop(mesh)
    if len(loop):
        Bp = V[loop]
        Bp_H = (Bp - p0) @ axis
        rim_sel = Bp_H > Bp_H.max() - max(0.5, 0.02*R_max_display)
        if rim_sel.sum() > 5:
            ra = (Bp[rim_sel] - p0) @ e_a
            rb = (Bp[rim_sel] - p0) @ e_b
            xc, yc, _, _ = fit_circle_2d(np.column_stack([ra, rb]))
            ra -= xc; rb -= yc
            avg_r = np.mean(np.sqrt(ra*ra + rb*rb))
            if avg_r > 0:
                scale = R_rim / avg_r
                ax_rim.plot(ra*scale, rb*scale, "k-", lw=2.2,
                             label=f"preserved arc ({result.preserved_arc_deg:.0f}°)")
    ax_rim.plot(0, 0, "k+", ms=12, mew=1.5)
    ax_rim.set_aspect("equal")
    ax_rim.set_xlim(-R_max_display*1.2, R_max_display*1.2)
    ax_rim.set_ylim(-R_max_display*1.2, R_max_display*1.2)
    ax_rim.set_title("Rim plan (from above)", fontsize=10)
    ax_rim.set_xlabel(unit, fontsize=8)
    ax_rim.tick_params(labelsize=7)
    ax_rim.grid(alpha=0.3, lw=0.4)
    ax_rim.legend(fontsize=7, loc="lower center")

    # ── Metadata block ──
    form_class = getattr(result, 'form_class', 'unknown')
    form_aspect = getattr(result, 'form_aspect_ratio', 0.0)
    rim_eversion = getattr(result, 'rim_eversion_deg', 0.0)
    meta_lines = [
        f"{result.name}",
        f"Mesh: {result.n_vertices:,} v / {result.n_faces:,} f",
        f"Units: {unit}",
        "─" * 21,
        f"Form: {form_class.upper()}",
        f"  aspect h/r: {form_aspect:.2f}",
        f"  rim eversion: {rim_eversion:.0f}°",
        "─" * 21,
        f"Rim Ø: {result.rim_diameter:.1f} {unit}",
        f"Max body Ø: {result.max_diameter:.1f} {unit}",
        f"Shoulder at: -{result.shoulder_height:.1f} {unit}",
        f"Preserved arc: {result.preserved_arc_deg:.0f}°",
        f"Preserved height: {result.preserved_height:.1f} {unit}",
        "─" * 21,
        f"Fit RMSE: {result.fit_residual_rmse:.2f} {unit}",
        f"Quality: {result.quality.upper()}",
    ]
    fig.text(0.015, 0.985, "\n".join(meta_lines), fontsize=7.3,
              family="monospace", va="top",
              bbox=dict(facecolor="#fafafa", edgecolor="gray", lw=0.5, pad=6))

    if save_to_disk:
        fig.savefig(str(out_png), dpi=200, bbox_inches="tight", facecolor="white")
        fig.savefig(str(out_svg), bbox_inches="tight", facecolor="white")
        plt.close(fig)
    # else: caller (GUI) owns the figure and will draw it on its canvas

    # Record per-stage timings for the GUI to display.
    _stage('done')
    _t = _stage_t
    n_faces = int(len(F))
    # NOTE: do NOT call _LAST_TIMING.clear() here — the GPU/CPU branch
    # earlier in the function has already populated rast_path,
    # gpu_attempted, gpu_succeeded, gpu_call_ms, polycoll_ms. Wiping the
    # dict here would lose those.
    _LAST_TIMING['n_faces'] = n_faces
    if 'clip_start' in _t and 'rasterise_start' in _t:
        _LAST_TIMING['pre_clip_ms']  = (_t['clip_start']      - _t_render_start) * 1000
        _LAST_TIMING['clip_ms']      = (_t['rasterise_start'] - _t['clip_start']) * 1000
        _LAST_TIMING['rasterise_ms'] = (_t['done']            - _t['rasterise_start']) * 1000
    if 'pre_a' in _t:
        _LAST_TIMING['pre_a_ms'] = (_t['pre_a'] - _t_render_start) * 1000
    if 'pre_a' in _t and 'pre_b' in _t:
        _LAST_TIMING['pre_b_ms'] = (_t['pre_b'] - _t['pre_a']) * 1000
    if 'pre_b' in _t and 'pre_c' in _t:
        _LAST_TIMING['pre_c_ms'] = (_t['pre_c'] - _t['pre_b']) * 1000
    if 'pre_c' in _t and 'clip_start' in _t:
        _LAST_TIMING['pre_d_ms'] = (_t['clip_start'] - _t['pre_c']) * 1000
    _LAST_TIMING['total_ms']         = (_t['done']            - _t_render_start) * 1000
    _LAST_TIMING['gpu_render']       = bool(gpu_render)
    _LAST_TIMING['version']          = _VERSION_TAG


def render_preview(mesh, out_png):
    """Render the 6-view orthographic preview (useful before processing)."""
    V = np.asarray(mesh.vertices); F = np.asarray(mesh.faces)
    Nf = np.asarray(mesh.face_normals)
    views = [
        ([0,0,-1], [0,1,0], "+Z (top)"),
        ([0,0,1],  [0,1,0], "-Z (bottom)"),
        ([-1,0,0], [0,0,1], "+X"),
        ([1,0,0],  [0,0,1], "-X"),
        ([0,-1,0], [0,0,1], "+Y"),
        ([0,1,0],  [0,0,1], "-Y"),
    ]
    fig, axes = plt.subplots(2, 3, figsize=(15, 10))
    for ax, (vd, up, t) in zip(axes.flat, views):
        vd = np.array(vd, dtype=float); vd /= np.linalg.norm(vd)
        up = np.array(up, dtype=float)
        right = np.cross(vd, up); right /= np.linalg.norm(right)
        up = np.cross(right, vd); up /= np.linalg.norm(up)
        P2 = np.column_stack([V @ right, V @ up])
        depth = V @ (-vd)
        tri_depth = depth[F].mean(axis=1); order = np.argsort(tri_depth)
        tris = P2[F][order]
        light = -vd + 0.3*up; light /= np.linalg.norm(light)
        sh = np.clip(Nf[order] @ light, 0, 1) * 0.7 + 0.3
        cols = np.column_stack([sh, sh, sh*0.95])
        front = Nf[order] @ (-vd) > -0.2
        ax.add_collection(PolyCollection(tris[front], facecolors=cols[front],
                                         edgecolors="none"))
        lo = P2.min(0); hi = P2.max(0)
        ax.set_xlim(lo[0]-3, hi[0]+3); ax.set_ylim(lo[1]-3, hi[1]+3)
        ax.set_aspect("equal"); ax.set_title(t, fontsize=10)
    fig.tight_layout()
    fig.savefig(str(out_png), dpi=130, bbox_inches="tight")
    plt.close(fig)


# =============================================================================
# Layer 1: Robust Orientation Pipeline
# =============================================================================
# This module provides three functions that work together to substantially
# improve auto-orientation reliability:
#
#   1. compute_wall_thickness_along_axis(mesh, axis, p0)
#        Measures local wall thickness as a function of height along the axis.
#        Returns (heights, thicknesses) arrays.
#
#   2. detect_rim_end_by_thickness(mesh, axis, p0)
#        Uses #1 to decide which end of the axis range is the rim. Rims are
#        almost always thicker than break edges (rolled, beaded, everted lips
#        thicken the wall; breaks have uniform thickness). Returns +1 (rim is
#        at +axis end) or -1 (rim is at -axis end), with a confidence score.
#
#   3. orient_and_analyze_robust(mesh)
#        End-to-end orientation pipeline:
#          a. Try multi-candidate auto-orient (PCA + 24 axis candidates)
#          b. Iteratively refine axis from horizontal slices to convergence
#          c. Disambiguate rim direction using wall thickness AND boundary
#             smoothness AND circle-fit residual (three independent tests,
#             majority vote)
#          d. Return a fit dict + a quality report indicating which steps
#             succeeded/failed
#
# This sits ALONGSIDE the existing pick_best_axis / orient_rim_up pipeline
# rather than replacing it. The existing path remains for backward
# compatibility; new code paths should prefer orient_and_analyze_robust.


def compute_wall_thickness_along_axis(mesh, axis, p0, n_bands=20,
                                        verbose=False):
    """Measure local wall thickness as a function of height along axis.

    Wall thickness = perpendicular distance from outer surface to inner
    surface, measured at multiple heights along the axis.

    Algorithm (revised — does not depend on consistent vertex normals,
    and computes its own axis center rather than trusting the caller's
    p0 which may be the vertex centroid rather than the symmetry axis
    center):
      1. Fit a circle to a horizontal slice near the middle of the
         mesh, in the (e_a, e_b) plane perpendicular to axis. Use that
         circle's center as the true axis center for radial calcs.
      2. Project all vertices onto axis to get height h, and onto the
         rim plane to get radial distance r and angle θ from axis.
      3. For each (height band, angular bin), look at the spread of
         radial values. If they show two clusters separated by a gap,
         the gap is the local wall thickness.
      4. Take the median thickness per band as a robust estimate.

    Returns:
        heights: (n_bands,) array of band-center heights
        thicknesses: (n_bands,) array of median wall thicknesses (NaN
                     where no valid pairs were found)
    """
    V = np.asarray(mesh.vertices)
    if len(V) == 0:
        return np.array([]), np.array([])
    # Build orthonormal frame {axis, e_a, e_b}
    axis = axis / np.linalg.norm(axis)
    tmp = np.array([1.0, 0, 0]) if abs(axis[0]) < 0.9 else np.array([0, 1.0, 0])
    e_a = np.cross(axis, tmp); e_a /= np.linalg.norm(e_a)
    e_b = np.cross(axis, e_a); e_b /= np.linalg.norm(e_b)

    # ── Find proper axis center ──
    # The caller's p0 may just be the vertex centroid, which is NOT the
    # symmetry axis center for partial-arc sherds. Fit a circle to a
    # horizontal slice through the middle of the mesh.
    Vrel0 = V - p0
    h0 = Vrel0 @ axis
    h_min, h_max = float(h0.min()), float(h0.max())
    h_mid_lo = h_min + 0.4 * (h_max - h_min)
    h_mid_hi = h_min + 0.6 * (h_max - h_min)
    mid_mask = (h0 >= h_mid_lo) & (h0 <= h_mid_hi)
    if mid_mask.sum() >= 8:
        a_mid = (V[mid_mask] - p0) @ e_a
        b_mid = (V[mid_mask] - p0) @ e_b
        try:
            cx, cy, R_fit, _ = fit_circle_2d(np.column_stack([a_mid, b_mid]))
            if np.isfinite(R_fit) and 0.1 < R_fit < 100:
                # Move axis center: p0_new = p0 + cx*e_a + cy*e_b
                p0_corrected = p0 + cx * e_a + cy * e_b
            else:
                p0_corrected = p0
        except Exception:
            p0_corrected = p0
    else:
        p0_corrected = p0

    Vrel = V - p0_corrected
    h = Vrel @ axis
    a_coord = Vrel @ e_a
    b_coord = Vrel @ e_b
    r = np.sqrt(a_coord * a_coord + b_coord * b_coord)
    theta = np.arctan2(b_coord, a_coord)

    h_min, h_max = float(h.min()), float(h.max())
    h_range = h_max - h_min
    if h_range < 0.01:
        return np.array([]), np.array([])

    band_edges = np.linspace(h_min, h_max, n_bands + 1)
    band_centers = 0.5 * (band_edges[:-1] + band_edges[1:])
    thicknesses = np.full(n_bands, np.nan)

    n_angle_bins = 12

    for i in range(n_bands):
        mask_band = (h >= band_edges[i]) & (h < band_edges[i + 1])
        if mask_band.sum() < 20:
            continue
        band_r = r[mask_band]
        band_theta = theta[mask_band]
        bin_idx = ((band_theta + np.pi) / (2 * np.pi) * n_angle_bins).astype(int)
        bin_idx = np.clip(bin_idx, 0, n_angle_bins - 1)

        per_bin_thickness = []
        for ang_b in range(n_angle_bins):
            r_in_b = band_r[bin_idx == ang_b]
            if len(r_in_b) < 4:
                continue
            r_sorted = np.sort(r_in_b)
            gaps = np.diff(r_sorted)
            if len(gaps) == 0:
                continue
            max_gap_idx = int(np.argmax(gaps))
            max_gap = float(gaps[max_gap_idx])
            r_total_range = float(r_sorted[-1] - r_sorted[0])
            if (max_gap > 0.05 and max_gap > 0.5 * r_total_range):
                inner_pts = r_sorted[:max_gap_idx + 1]
                outer_pts = r_sorted[max_gap_idx + 1:]
                if len(inner_pts) >= 2 and len(outer_pts) >= 2:
                    t = float(np.median(outer_pts) - np.median(inner_pts))
                    if t > 0.05:
                        per_bin_thickness.append(t)

        if len(per_bin_thickness) >= 3:
            thicknesses[i] = float(np.median(per_bin_thickness))

    if verbose:
        valid = ~np.isnan(thicknesses)
        if valid.any():
            print(f"   wall thickness: {valid.sum()}/{n_bands} bands, "
                  f"range {np.nanmin(thicknesses):.2f}–{np.nanmax(thicknesses):.2f}")
        else:
            print("   wall thickness: no valid bands found "
                  "(mesh may be single-sided or axis center is wrong)")

    return band_centers, thicknesses


def detect_rim_end_by_thickness(mesh, axis, p0, verbose=False):
    """Decide which end of the axis range is the rim, using wall thickness.

    Real rims are thickened (rolled, beaded, everted) compared to the
    body wall. Broken edges are usually NOT thickened — they have the
    same wall thickness as the body.

    Algorithm:
      - Compute wall thickness along axis (in 20 bands).
      - For the +axis end (top 20% of bands) and -axis end (bottom 20%),
        compare median thickness to the middle 60% (the body baseline).
      - The end whose thickness exceeds the body baseline by a larger
        margin is the rim.
      - Return +1 if rim is at +axis end (no flip needed), -1 if at
        -axis end (axis should be flipped).

    Returns:
        sign: +1, -1, or 0 (if undecidable due to insufficient data)
        confidence: float in [0, 1] (margin between top and bottom)
        info: dict with diagnostic values
    """
    heights, thicknesses = compute_wall_thickness_along_axis(
        mesh, axis, p0, n_bands=20, verbose=False)
    if len(heights) == 0:
        return 0, 0.0, {"reason": "no thickness data"}

    valid = ~np.isnan(thicknesses)
    if valid.sum() < 8:
        return 0, 0.0, {"reason": f"only {valid.sum()} valid bands"}

    n = len(heights)
    top_idx = slice(int(0.80 * n), n)
    bot_idx = slice(0, int(0.20 * n))
    mid_idx = slice(int(0.20 * n), int(0.80 * n))

    top_t = thicknesses[top_idx]; top_t = top_t[~np.isnan(top_t)]
    bot_t = thicknesses[bot_idx]; bot_t = bot_t[~np.isnan(bot_t)]
    mid_t = thicknesses[mid_idx]; mid_t = mid_t[~np.isnan(mid_t)]

    if len(top_t) < 2 or len(bot_t) < 2 or len(mid_t) < 4:
        return 0, 0.0, {"reason": "insufficient bands per region"}

    top_med = float(np.median(top_t))
    bot_med = float(np.median(bot_t))
    mid_med = float(np.median(mid_t))

    # Excess thickness over body baseline for each end
    top_excess = top_med - mid_med
    bot_excess = bot_med - mid_med

    info = {
        "top_thickness": top_med, "bot_thickness": bot_med,
        "mid_thickness": mid_med,
        "top_excess": top_excess, "bot_excess": bot_excess,
    }

    # Decision: pick the end with greater positive excess
    if top_excess > bot_excess + 0.01:
        sign = +1
        margin = top_excess - bot_excess
    elif bot_excess > top_excess + 0.01:
        sign = -1
        margin = bot_excess - top_excess
    else:
        sign = 0
        margin = abs(top_excess - bot_excess)

    # Confidence: normalized margin relative to body thickness
    confidence = min(1.0, margin / max(0.1, mid_med))
    info["sign"] = sign
    info["confidence"] = confidence

    if verbose:
        print(f"   wall thickness rim detection: top={top_med:.2f} "
              f"bot={bot_med:.2f} body={mid_med:.2f} "
              f"-> sign={sign:+d} conf={confidence:.2f}")

    return sign, confidence, info


def refine_axis_iteratively(mesh, init_axis, init_p0, max_iter=8,
                              tol_deg=0.5, verbose=False):
    """Refit the axis from horizontal slices, repeating until stable.

    Each iteration calls multi_slice_axis_fit using the current axis as
    the hint, then checks how much the axis direction changed. Stops
    when the change is below tol_deg or after max_iter iterations.

    Returns: final fit dict, plus a list of axis directions across
    iterations for diagnosis.
    """
    axis = init_axis / np.linalg.norm(init_axis)
    p0 = init_p0
    history = [axis.copy()]
    fit = None

    for it in range(max_iter):
        try:
            fit = multi_slice_axis_fit(mesh, axis, verbose=False)
        except Exception as e:
            if verbose:
                print(f"   iter {it}: refit failed ({e}), keeping last")
            break
        if fit is None:
            break
        new_axis = fit["axis_dir"]
        # Compute angle between old and new axis
        cos_change = float(np.clip(abs(np.dot(axis, new_axis)), -1, 1))
        angle_change = np.degrees(np.arccos(cos_change))
        history.append(new_axis.copy())
        if verbose:
            print(f"   iter {it}: axis change {angle_change:.2f}° "
                  f"RMSE {fit.get('rmse', float('nan')):.3f}")
        axis = new_axis
        p0 = fit["axis_point"]
        if angle_change < tol_deg:
            if verbose:
                print(f"   converged after {it+1} iterations")
            break

    if fit is not None:
        fit["_refinement_history"] = history
        fit["_refinement_iterations"] = len(history) - 1
    return fit


def orient_and_analyze_robust(mesh, verbose=False):
    """End-to-end Layer 1 orientation pipeline.

    Steps:
      1. Generate ~24 candidate axis directions: 3 cardinal axes + their
         negatives + 18 oblique ones from PCA + spherical sampling.
      2. Run multi_slice_axis_fit on each candidate, score by combined
         metric (low RMSE, high r_var, large arc, low flat-axis penalty).
      3. Pick the top 3 candidates.
      4. For each top candidate, refine axis iteratively.
      5. Pick final candidate by re-scoring after refinement.
      6. Disambiguate rim direction using THREE independent tests:
         a. Wall thickness (rims are thicker than breaks)
         b. Boundary smoothness (rims are smoother than breaks)
         c. Circle-fit residual (rims fit a circle better than breaks)
         Majority vote among the three. If all 3 agree, high confidence.
         If 2 agree, medium. If split (1-1-1 unlikely but possible), use
         the one with highest individual confidence.
      7. Apply the rim-up flip if needed.

    Returns:
        fit: standard fit dict (axis_dir, axis_point, e_a, e_b,
             profile_h, profile_r, rmse, ...)
        report: dict with per-step diagnostics for debugging:
                - 'candidates_tried': int
                - 'best_initial_score': float
                - 'refinement_iterations': int
                - 'rim_disambiguation': {'thickness': sign, 'smoothness': sign,
                                         'circle': sign, 'final': sign,
                                         'votes_agreeing': int,
                                         'confidence': str}
                - 'overall_quality': 'GOOD' | 'WARN' | 'BAD'
                - 'warnings': list of strings
    """
    report = {"warnings": []}

    # Step 1: Generate candidates
    candidates = []
    # Cardinal + antipodal
    for c in [np.array([1.0, 0, 0]), np.array([-1.0, 0, 0]),
              np.array([0, 1.0, 0]), np.array([0, -1.0, 0]),
              np.array([0, 0, 1.0]), np.array([0, 0, -1.0])]:
        candidates.append(c)
    # PCA principal axes
    V = np.asarray(mesh.vertices)
    if len(V) > 10:
        try:
            Vc = V - V.mean(axis=0)
            cov = (Vc.T @ Vc) / len(V)
            evals, evecs = np.linalg.eigh(cov)
            for i in range(3):
                candidates.append(evecs[:, i])
                candidates.append(-evecs[:, i])
        except Exception:
            pass
    # Spherical sampling: ~12 evenly-spaced directions on the upper hemisphere
    for phi in np.linspace(0, 2 * np.pi, 6, endpoint=False):
        for elev in [np.pi / 6, np.pi / 3]:
            x = np.cos(elev) * np.cos(phi)
            y = np.sin(elev)
            z = np.cos(elev) * np.sin(phi)
            candidates.append(np.array([x, y, z]))

    # Deduplicate near-identical candidates
    deduped = []
    for c in candidates:
        c_norm = c / np.linalg.norm(c)
        is_dup = any(abs(np.dot(c_norm, d / np.linalg.norm(d))) > 0.97
                     for d in deduped)
        if not is_dup:
            deduped.append(c_norm)
    candidates = deduped
    report["candidates_tried"] = len(candidates)

    # Step 2: Score each candidate
    extents = mesh.extents
    short_axis_idx = int(np.argmin(extents))
    scored = []
    for c in candidates:
        try:
            fit = multi_slice_axis_fit(mesh, c, verbose=False)
        except Exception:
            continue
        if (fit is None or fit.get("profile_r") is None
                or len(fit["profile_r"]) < 3):
            continue
        rmse = fit["rmse"]
        r_range = float(fit["profile_r"].max() - fit["profile_r"].min())
        c_abs = np.abs(c)
        flat_pen = 1.0 if c_abs[short_axis_idx] > 0.9 else 0.0
        score = r_range / max(0.5, rmse) - 2 * flat_pen
        scored.append((score, fit))

    if not scored:
        report["overall_quality"] = "BAD"
        report["warnings"].append("no candidate axis produced a valid fit")
        return None, report

    scored.sort(key=lambda x: x[0], reverse=True)
    report["best_initial_score"] = scored[0][0]
    top_candidates = scored[:3]

    # Step 4-5: Refine each top candidate, pick best after refinement
    refined = []
    for score, fit in top_candidates:
        try:
            ref = refine_axis_iteratively(mesh, fit["axis_dir"],
                                            fit["axis_point"],
                                            max_iter=6, verbose=False)
        except Exception:
            ref = fit
        if ref is not None and ref.get("profile_r") is not None:
            r_range = float(ref["profile_r"].max() - ref["profile_r"].min())
            new_score = r_range / max(0.5, ref["rmse"])
            refined.append((new_score, ref))

    if not refined:
        report["overall_quality"] = "WARN"
        report["warnings"].append(
            "axis refinement failed; using unrefined candidate")
        fit = scored[0][1]
    else:
        refined.sort(key=lambda x: x[0], reverse=True)
        fit = refined[0][1]
        report["refinement_iterations"] = fit.get(
            "_refinement_iterations", 0)

    # Step 6: Three-way rim disambiguation
    axis = fit["axis_dir"]; p0 = fit["axis_point"]
    V = np.asarray(mesh.vertices)
    H_all = (V - p0) @ axis
    H_top = float(H_all.max()); H_bot = float(H_all.min())

    # Test A: wall thickness
    sign_thick, conf_thick, _ = detect_rim_end_by_thickness(
        mesh, axis, p0, verbose=False)

    # Test B: boundary smoothness (rim end has lower MAD/median radial
    # variation in boundary points)
    sign_smooth = 0; conf_smooth = 0.0
    try:
        loop = get_boundary_loop(mesh)
        if len(loop) > 30:
            B = V[loop]
            B_proj = (B - p0) @ axis
            B_perp = np.sqrt(
                np.einsum('ij,ij->i', B - p0, B - p0) - B_proj ** 2)
            band = 0.20 * (H_top - H_bot)
            top_mask = B_proj > H_top - band
            bot_mask = B_proj < H_bot + band

            def smoothness(B_perp_sub):
                if len(B_perp_sub) < 8: return None
                med_r = float(np.median(B_perp_sub))
                if med_r < 0.01: return None
                mad = float(np.median(np.abs(B_perp_sub - med_r)))
                return mad / med_r  # lower = smoother

            top_smooth = smoothness(B_perp[top_mask])
            bot_smooth = smoothness(B_perp[bot_mask])
            if top_smooth is not None and bot_smooth is not None:
                # Lower variation = smoother = more likely rim
                if top_smooth < bot_smooth - 0.005:
                    sign_smooth = +1
                    conf_smooth = min(1.0, (bot_smooth - top_smooth) / 0.05)
                elif bot_smooth < top_smooth - 0.005:
                    sign_smooth = -1
                    conf_smooth = min(1.0, (top_smooth - bot_smooth) / 0.05)
    except Exception:
        pass

    # Test C: circle-fit residual on top vs bottom boundary band
    sign_circle = 0; conf_circle = 0.0
    try:
        loop = get_boundary_loop(mesh)
        if len(loop) > 30:
            Bp = V[loop]
            Bp_H = (Bp - p0) @ axis
            band = (H_top - H_bot) * 0.05
            top_pts = Bp[Bp_H > H_top - band]
            bot_pts = Bp[Bp_H < H_bot + band]

            def circle_resid(pts):
                if len(pts) < 8: return None
                ca = (pts - p0) @ fit["e_a"]
                cb = (pts - p0) @ fit["e_b"]
                _, _, R, res = fit_circle_2d(np.column_stack([ca, cb]))
                if not np.isfinite(R) or R < 0.1: return None
                return res / R  # relative residual

            top_res = circle_resid(top_pts)
            bot_res = circle_resid(bot_pts)
            if top_res is not None and bot_res is not None:
                # Lower relative residual = better circle fit = more likely rim
                if top_res < bot_res - 0.01:
                    sign_circle = +1
                    conf_circle = min(1.0, (bot_res - top_res) / 0.10)
                elif bot_res < top_res - 0.01:
                    sign_circle = -1
                    conf_circle = min(1.0, (top_res - bot_res) / 0.10)
    except Exception:
        pass

    # Majority vote
    votes = [sign_thick, sign_smooth, sign_circle]
    confidences = [conf_thick, conf_smooth, conf_circle]
    nonzero_votes = [v for v in votes if v != 0]

    if not nonzero_votes:
        # All three undecided — keep current orientation
        sign_final = +1
        rim_confidence = "low"
        report["warnings"].append(
            "all three rim-detection tests were inconclusive; "
            "keeping default orientation")
    else:
        # Sum signed votes weighted by confidence
        weighted_sum = sum(v * c for v, c in zip(votes, confidences))
        sign_final = +1 if weighted_sum >= 0 else -1
        votes_agreeing = sum(1 for v in nonzero_votes if v == sign_final)

        if votes_agreeing == 3:
            rim_confidence = "high"
        elif votes_agreeing == 2:
            rim_confidence = "medium"
        else:
            rim_confidence = "low"
            report["warnings"].append(
                f"rim-detection tests disagreed "
                f"(thickness={sign_thick:+d} smoothness={sign_smooth:+d} "
                f"circle={sign_circle:+d}); using weighted vote")

    report["rim_disambiguation"] = {
        "thickness": sign_thick, "thickness_conf": conf_thick,
        "smoothness": sign_smooth, "smoothness_conf": conf_smooth,
        "circle": sign_circle, "circle_conf": conf_circle,
        "final_sign": sign_final, "confidence": rim_confidence,
        "votes_agreeing": (sum(1 for v in nonzero_votes if v == sign_final)
                           if nonzero_votes else 0),
    }

    # Step 7: Apply flip if needed
    if sign_final < 0:
        fit["axis_dir"] = -fit["axis_dir"]
        if fit.get("profile_h") is not None:
            fit["profile_h"] = -fit["profile_h"][::-1]
        if fit.get("profile_r") is not None:
            fit["profile_r"] = fit["profile_r"][::-1]

    # Final quality assessment
    if rim_confidence == "high" and fit.get("rmse", float("inf")) < 0.5:
        report["overall_quality"] = "GOOD"
    elif rim_confidence in ("medium", "high"):
        report["overall_quality"] = "WARN"
    else:
        report["overall_quality"] = "WARN"
        if rim_confidence == "low":
            report["warnings"].append(
                "low confidence in rim direction — verify visually")

    if verbose:
        print(f"   Layer 1 orient: quality={report['overall_quality']} "
              f"rim={rim_confidence} (thick={sign_thick:+d}/"
              f"smooth={sign_smooth:+d}/circle={sign_circle:+d}) "
              f"refine_iter={report.get('refinement_iterations', 0)}")
        for w in report["warnings"]:
            print(f"   [WARN] {w}")

    return fit, report


# =============================================================================
# Main per-sherd pipeline
# =============================================================================

def process_single_mesh(mesh, name, mesh_path, output_dir, unit="auto", axis_hint="auto",
                        verbose=False, preview_only=False, auto_orient=False,
                        manual_rim_diameter=None):
    extent = mesh.extents.tolist()
    result = SherdResult(
        name=name, path=str(mesh_path),
        n_vertices=len(mesh.vertices), n_faces=len(mesh.faces),
        bbox_extent=tuple(extent),
    )

    # Unit handling
    if unit == "auto":
        guess = detect_units(max(extent))
        if guess == "unknown":
            source_unit = "cm"
            result.notes.append(f"unit auto-detect inconclusive (max dim={max(extent):.3f}), defaulted to cm")
        else:
            source_unit = guess
            if verbose: print(f"   auto-detected unit: {guess}")
    else:
        source_unit = unit

    # Normalize mesh internally to cm so all downstream math is in cm
    scale = scale_for_unit(source_unit)
    if scale != 1.0:
        if verbose: print(f"   rescaling mesh from {source_unit} to cm (×{scale})")
        mesh.apply_scale(scale)
        result.bbox_extent = tuple((np.asarray(extent) * scale).tolist())
    result.unit = "cm"

    # Auto-orient: scientifically determine vessel axis and rotate mesh to rim-up
    if auto_orient:
        if verbose: print("   auto-orienting mesh from rim geometry...")
        orient = auto_orient_from_rim(mesh, verbose=verbose)
        if orient is not None:
            R = orient["R"]
            mesh.apply_transform(np.block([
                [R, np.zeros((3, 1))],
                [np.zeros((1, 3)), np.array([[1.0]])]
            ]))
            inc = orient["inclination_deg"]
            quality = orient["rim_info"]["quality"]
            note = (f"auto-oriented: detected axis {orient['axis_orig'].round(3).tolist()}, "
                    f"inclination from +Y={inc:.2f}°, rim fit quality={quality}")
            if verbose: print(f"   {note}")
            result.notes.append(note)
            # After rotation, the axis is now +Y, so override axis_hint
            axis_hint = "Y"
        else:
            result.notes.append("auto-orient failed; using axis_hint as-is")
            if verbose: print("   auto-orient failed; falling back")

    if preview_only:
        render_preview(mesh, output_dir / f"{name}_preview.png")
        if verbose: print(f"   wrote preview.")
        return result

    # Axis fitting
    if verbose: print("   fitting axis of symmetry (multi-slice)...")
    layer1_report_proc = None
    if axis_hint == "auto":
        # Layer 1: robust multi-candidate orientation
        try:
            fit, layer1_report_proc = orient_and_analyze_robust(
                mesh, verbose=verbose)
        except Exception as e:
            if verbose:
                print(f"   Layer 1 robust orient failed ({e}); "
                      f"falling back to pick_best_axis")
            fit = pick_best_axis(mesh, verbose=verbose)
            layer1_report_proc = None
    elif axis_hint in ("X", "Y", "Z"):
        init = np.zeros(3); init["XYZ".index(axis_hint)] = 1.0
        fit = multi_slice_axis_fit(mesh, init, verbose=verbose)
    else:
        # Custom axis direction like "0.1,0.9,0.2"
        try:
            init = np.array([float(v) for v in axis_hint.split(",")])
            fit = multi_slice_axis_fit(mesh, init, verbose=verbose)
        except Exception:
            raise ValueError(f"Could not parse axis hint: {axis_hint}")

    if fit is None:
        result.quality = "bad"
        result.notes.append("axis fit failed — consider --interactive mode")
        if verbose: print("   [FAIL] axis fit failed")
        return result

    # If Layer 1 ran, it already did rim-up disambiguation; skip the legacy
    # orient_rim_up call to avoid double-flipping. Otherwise apply legacy.
    if layer1_report_proc is not None:
        if verbose:
            rd = layer1_report_proc.get("rim_disambiguation", {})
            print(f"   Layer 1: candidates={layer1_report_proc.get('candidates_tried')} "
                  f"refine_iter={layer1_report_proc.get('refinement_iterations', '?')} "
                  f"rim_conf={rd.get('confidence', '?')}")
        for w in layer1_report_proc.get("warnings", []):
            result.notes.append(f"Layer 1: {w}")
    else:
        fit = orient_rim_up(mesh, fit)

    # Check if the fitted axis is degenerate (passing too close to the sherd)
    # Only apply if user is using automatic axis alignment
    if axis_hint == "auto":
        centroid = mesh.vertices.mean(axis=0)
        rel_c = centroid - np.array(fit["axis_point"])
        dist_to_axis = np.linalg.norm(rel_c - np.dot(rel_c, fit["axis_dir"]) * fit["axis_dir"])
        
        if dist_to_axis < 15.0 or fit.get("rmse", 0) > 2.0:
            if verbose:
                print("   [WARN] degenerate axis fit detected (dist < 15cm). Falling back to Y-axis.")
            fit["axis_point"] = np.array([0.0, 0.0, 0.0])
            fit["axis_dir"] = np.array([0.0, 1.0, 0.0])
            fit["rmse"] = 0.0
            fit["e_a"] = np.array([1.0, 0.0, 0.0])
            fit["e_b"] = np.array([0.0, 0.0, 1.0])
            fit["profile_h"] = None
            fit["profile_r"] = None
            
            # Ensure Y-axis coordinates are updated in the result
            result.axis_dir = (0.0, 1.0, 0.0)
            result.axis_point = (0.0, 0.0, 0.0)
            result.fit_residual_rmse = 0.0

    # ── DEGENERATE FIT DETECTION & REFIT for shallow forms ──
    # Multi-slice axis fits on shallow rim sherds (bowls/plates with small
    # preserved arc) are poorly conditioned: many tilted axes fit the partial
    # arc nearly equally well, and the iterative refinement often converges
    # to a tilted axis that's geometrically wrong even though its RMSE looks
    # better than the un-tilted one.
    #
    # Detection: estimate the form using the FIRST fit's profile. If the
    # preserved height is much smaller than the rim radius (shallow form)
    # AND the converged axis deviates significantly from the initial guess,
    # the refinement is likely degenerate. Refit with axis-deviation clamp.
    prof_h0 = fit.get("profile_h"); prof_r0 = fit.get("profile_r")
    if (prof_h0 is not None and prof_r0 is not None and len(prof_r0) >= 3
            and axis_hint in ("X", "Y", "Z")):
        h_range = float(prof_h0.max() - prof_h0.min())
        r_max_quick = float(prof_r0.max())
        aspect_quick = h_range / max(r_max_quick, 0.5)
        # Determine starting axis hint
        init_axis = np.zeros(3)
        init_axis["XYZ".index(axis_hint)] = 1.0
        # How far did the fit drift from the initial axis hint?
        cos_dev = float(np.clip(abs(fit["axis_dir"] @ init_axis), -1, 1))
        drift_deg = float(np.degrees(np.arccos(cos_dev)))
        is_shallow = aspect_quick < 0.5
        if is_shallow and drift_deg > 8:
            if verbose:
                print(f"   shallow form detected (h/r={aspect_quick:.2f}) "
                      f"and axis drifted {drift_deg:.1f}° — refitting with "
                      f"5° axis-deviation clamp (multi-slice fits are "
                      f"poorly conditioned for shallow rim sherds)...")
            fit2 = multi_slice_axis_fit(mesh, init_axis, verbose=verbose,
                                         max_axis_deviation_deg=5.0)
            if fit2 is not None:
                fit = orient_rim_up(mesh, fit2)
                if verbose:
                    new_drift = np.degrees(np.arccos(
                        abs(np.clip(fit["axis_dir"] @ init_axis, -1, 1))))
                    print(f"   refit axis deviates {new_drift:.1f}° "
                          f"from initial Y, RMSE={fit['rmse']:.3f}")

    result.axis_dir = tuple(fit["axis_dir"].tolist())
    result.axis_point = tuple(fit["axis_point"].tolist())
    result.fit_residual_rmse = fit["rmse"]

    # Set default values for rim-related attributes
    result.rim_diameter = 0.0
    result.max_diameter = 0.0
    result.shoulder_height = 0.0
    result.preserved_arc_deg = 0.0
    
    prof_h = fit.get("profile_h")
    prof_r = fit.get("profile_r")
    if prof_h is not None and len(prof_h) > 0:
        result.preserved_height = float(prof_h.max() - prof_h.min())
    else:
        result.preserved_height = float(mesh.extents[1]) # fallback

    result.form_class = "unknown"
    result.quality = "good"

    # Save the profile (axis fit) JSON as soon as it's available, not only after a
    # successful cross-section extraction below - prof_h/prof_r are already valid here,
    # and section extraction can fail independently (e.g. "section extraction failed")
    # even when the axis/profile fit itself succeeded. The fuller write further down
    # (with section_azimuth_deg added) overwrites this one when it's reached.
    json_path = output_dir / f"{name}_fit.json"
    try:
        json.dump({
            "axis_dir": list(result.axis_dir),
            "axis_point": list(result.axis_point),
            "profile_h": prof_h.tolist() if prof_h is not None else [],
            "profile_r": prof_r.tolist() if prof_r is not None else [],
            "rim_diameter": 0.0,
            "max_diameter": 0.0,
            "unit": result.unit,
            "section_azimuth_deg": None,
        }, open(json_path, "w"), indent=2)
    except Exception:
        pass

    # Cross-section
    if verbose: print("   extracting cross-section...")
    best = find_best_section_azimuth(mesh, fit)
    if best is None:
        result.notes.append("section extraction failed")
        result.quality = "bad"
        return result
    az_deg, radial_dir, sec = best
    section_poly, area = extract_section_polygon(sec, fit["axis_point"],
                                                  fit["axis_dir"], radial_dir)
    if section_poly is None:
        result.notes.append("no valid section polygon")
        result.quality = "bad"
        return result
    result.section_azimuth_deg = az_deg

    # Render preview
    preview_png = output_dir / f"{name}_preview.png"
    if verbose: print(f"   rendering preview -> {preview_png.name}")
    try:
        render_preview(mesh, preview_png)
    except Exception as e:
        print(f"   [WARN] preview rendering failed: {e}")

    # Render curvature & thickness plot
    curvature_png = output_dir / f"{name}_curvature_thickness.png"
    if verbose: print(f"   rendering curvature & thickness plot -> {curvature_png.name}")
    try:
        plot_sherd_curvature_thickness(
            section_poly, 
            title=f"{name} Curvature & Thickness Analysis", 
            save_path=curvature_png,
            mesh=mesh,
            fit=fit,
            az_deg=az_deg
        )
    except Exception as e:
        print(f"   [WARN] curvature & thickness plot failed: {e}")

    # Save fit JSON (minimal)
    json_path = output_dir / f"{name}_fit.json"
    try:
        json.dump({
            "axis_dir": list(result.axis_dir),
            "axis_point": list(result.axis_point),
            "profile_h": prof_h.tolist() if prof_h is not None else [],
            "profile_r": prof_r.tolist() if prof_r is not None else [],
            "rim_diameter": 0.0,
            "max_diameter": 0.0,
            "unit": result.unit,
            "section_azimuth_deg": az_deg,
        }, open(json_path, "w"), indent=2)
    except Exception:
        pass

    if verbose:
        print(f"   thickness and curvature analysis completed for {name}")
    return result


def process_one(mesh_path, output_dir, unit="auto", axis_hint="auto",
                verbose=False, preview_only=False, auto_orient=False,
                manual_rim_diameter=None):
    mesh_path = Path(mesh_path); output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    if verbose: print(f"\n[{mesh_path.name}]  loading meshes...")
    meshes = load_meshes(mesh_path)
    
    if len(meshes) > 1:
        if verbose: print(f"   found {len(meshes)} objects in file. Processing as batch...")
        results = []
        for name, mesh in meshes:
            if verbose: print(f"\nProcessing sub-object: {name}")
            try:
                r = process_single_mesh(
                    mesh, name, mesh_path, output_dir, unit=unit, axis_hint=axis_hint,
                    verbose=verbose, preview_only=preview_only, auto_orient=auto_orient,
                    manual_rim_diameter=manual_rim_diameter
                )
                results.append(r)
            except Exception as e:
                print(f"   [ERROR] failed to process sub-object {name}: {e}")
                if verbose: traceback.print_exc()
                results.append(SherdResult(name=name, path=str(mesh_path), quality="bad", notes=[f"exception: {e}"]))
        return results
    else:
        name, mesh = meshes[0]
        # Use filename stem for single object to preserve standard behavior
        return process_single_mesh(
            mesh, mesh_path.stem, mesh_path, output_dir, unit=unit, axis_hint=axis_hint,
            verbose=verbose, preview_only=preview_only, auto_orient=auto_orient,
            manual_rim_diameter=manual_rim_diameter
        )


# =============================================================================
# Interactive mode (matplotlib sliders)
# =============================================================================

def interactive_mode(mesh_path, output_dir, unit="cm", verbose=False):
    """
    Opens an interactive matplotlib window with sliders for axis direction
    (theta, phi) and replots the profile in real time. User clicks 'Save' to
    accept the current fit and write the plate.
    """
    mesh_path = Path(mesh_path); output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    meshes = load_meshes(mesh_path)
    if len(meshes) > 1:
        print(f"Warning: {mesh_path.name} contains {len(meshes)} objects. Opening the first one ({meshes[0][0]}) in interactive mode.")
    name, mesh = meshes[0]

    # Start from auto-fit if possible
    fit = pick_best_axis(mesh, verbose=verbose)
    if fit is None:
        print("auto-fit failed; starting with Y axis")
        fit = multi_slice_axis_fit(mesh, np.array([0, 1.0, 0]), verbose=False)

    fig, (ax_prev, ax_prof) = plt.subplots(1, 2, figsize=(12, 7))
    plt.subplots_adjust(bottom=0.3)

    # plot preview on left
    V = np.asarray(mesh.vertices); F = np.asarray(mesh.faces); Nf = np.asarray(mesh.face_normals)
    def render_left():
        ax_prev.clear()
        # view from "side" (perpendicular to axis)
        axis = fit["axis_dir"]
        tmp = np.array([1.0,0,0]) if abs(axis[0])<0.9 else np.array([0,1.0,0])
        right = np.cross(axis, tmp); right /= np.linalg.norm(right)
        up = np.cross(right, axis); up /= np.linalg.norm(up)
        viewdir = np.cross(up, right); viewdir /= np.linalg.norm(viewdir)
        P2 = np.column_stack([V@right, V@up])
        depth = V@(-viewdir)
        tri_d = depth[F].mean(axis=1); order = np.argsort(tri_d)
        tris = P2[F][order]
        light = -viewdir + 0.3*up; light /= np.linalg.norm(light)
        sh = np.clip(Nf[order]@light, 0, 1)*0.6 + 0.4
        cols = np.column_stack([sh, sh, sh*0.95])
        front = Nf[order]@(-viewdir) > -0.2
        ax_prev.add_collection(PolyCollection(tris[front], facecolors=cols[front], edgecolors="none"))
        lo=P2.min(0); hi=P2.max(0); pad=2
        ax_prev.set_xlim(lo[0]-pad, hi[0]+pad); ax_prev.set_ylim(lo[1]-pad, hi[1]+pad)
        ax_prev.set_aspect("equal")
        ax_prev.set_title(f"Side view (axis vertical)\naxis dir = {axis.round(3)}")

    def render_right():
        ax_prof.clear()
        if fit.get("profile_h") is not None:
            H_rim = fit["profile_h"].max()
            ax_prof.plot(fit["profile_r"], fit["profile_h"] - H_rim, "k-o", ms=3)
            ax_prof.set_aspect("equal")
            ax_prof.set_xlabel(f"radius ({unit})"); ax_prof.set_ylabel(f"h below rim ({unit})")
            ax_prof.axvline(0, color="gray", ls=":")
            R_rim = fit["profile_r"][int(np.argmax(fit["profile_h"]))]
            R_max = fit["profile_r"].max()
            ax_prof.set_title(f"r(h) profile\nrim Ø {2*R_rim:.1f}  max Ø {2*R_max:.1f} {unit}\nRMSE {fit['rmse']:.2f}")
            ax_prof.grid(alpha=0.3)

    render_left(); render_right()

    # Sliders: theta (from Y) and phi (azimuth)
    d = fit["axis_dir"]
    theta0 = np.degrees(np.arccos(np.clip(d[1], -1, 1)))
    phi0 = np.degrees(np.arctan2(d[2], d[0]))
    ax_theta = plt.axes([0.15, 0.18, 0.7, 0.03])
    ax_phi   = plt.axes([0.15, 0.13, 0.7, 0.03])
    s_theta = Slider(ax_theta, "θ (from Y)", 0, 180, valinit=theta0, valstep=1)
    s_phi   = Slider(ax_phi, "φ (azimuth)", -180, 180, valinit=phi0, valstep=1)

    def recompute(_):
        th = np.radians(s_theta.val); ph = np.radians(s_phi.val)
        d_new = np.array([np.sin(th)*np.cos(ph), np.cos(th), np.sin(th)*np.sin(ph)])
        new_fit = multi_slice_axis_fit(mesh, d_new, verbose=False)
        if new_fit is not None:
            new_fit = orient_rim_up(mesh, new_fit)
            fit.update(new_fit)
            render_left(); render_right()
            fig.canvas.draw_idle()

    s_theta.on_changed(recompute); s_phi.on_changed(recompute)

    ax_save = plt.axes([0.4, 0.03, 0.2, 0.06])
    b_save = Button(ax_save, "Save plate")
    saved = {"done": False}
    def on_save(_):
        # finalize and write
        result = SherdResult(
            name=mesh_path.stem, path=str(mesh_path),
            n_vertices=len(mesh.vertices), n_faces=len(mesh.faces),
            bbox_extent=tuple(mesh.extents.tolist()),
            axis_dir=tuple(fit["axis_dir"].tolist()),
            axis_point=tuple(fit["axis_point"].tolist()),
            unit=unit,
            fit_residual_rmse=fit["rmse"],
        )
        prof_h = fit["profile_h"]; prof_r = fit["profile_r"]
        H_rim = prof_h.max()
        result.rim_diameter = float(2*prof_r[int(np.argmax(prof_h))])
        result.max_diameter = float(2*prof_r.max())
        idx_max = int(np.argmax(prof_r))
        result.shoulder_height = float(H_rim - prof_h[idx_max])
        result.preserved_height = float(H_rim - prof_h.min())
        result.preserved_arc_deg = compute_preserved_arc(mesh, fit)
        result.quality = "good" if result.fit_residual_rmse/(result.rim_diameter/2+1e-3) < 0.1 else "warn"
        best = find_best_section_azimuth(mesh, fit)
        if best:
            az_deg, radial_dir, sec = best
            pol, _ = extract_section_polygon(sec, fit["axis_point"], fit["axis_dir"], radial_dir)
            if pol is not None:
                result.section_azimuth_deg = az_deg
                out_png = output_dir / f"{mesh_path.stem}_plate.png"
                out_svg = output_dir / f"{mesh_path.stem}_plate.svg"
                render_plate(mesh, fit, pol, az_deg, result, out_png, out_svg)
                print(f"Saved: {out_png}")
        saved["done"] = True
        plt.close(fig)
    b_save.on_clicked(on_save)
    plt.show()


# =============================================================================
# Batch processing, CSV + HTML report
# =============================================================================

def write_csv(results, csv_path):
    if not results: return
    fields = list(results[0].to_flat_dict().keys())
    with open(csv_path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in results: w.writerow(r.to_flat_dict())


def write_html_gallery(results, output_dir):
    """Static HTML with a gallery of sherd plates + a measurements table."""
    output_dir = Path(output_dir)
    rows_html = []
    for r in results:
        plate = f"{r.name}_plate.png"
        plate_exists = (output_dir / plate).exists()
        preview = f"{r.name}_preview.png"
        preview_exists = (output_dir / preview).exists()
        img_src = plate if plate_exists else (preview if preview_exists else "")
        
        curv_img = f"{r.name}_curvature_thickness.png"
        curv_exists = (output_dir / curv_img).exists()
        
        quality_color = {"good":"#2d8f2d","warn":"#c98a00","bad":"#b23636","unknown":"#555"}[r.quality]
        rows_html.append(f"""
        <div class="sherd-card">
          <h3>{r.name}</h3>
          {f'<img src="{img_src}" loading="lazy">' if img_src else '<div class="no-img">no plate</div>'}
          {f'<div class="curv-link" style="margin-top:8px; text-align:center;"><a href="{curv_img}" target="_blank" style="color:#007aff; font-size:12px; text-decoration:none; font-weight:bold;">📈 View Curvature & Thickness Analysis</a></div>' if curv_exists else ''}
          <table>
            <tr><td>Rim Ø</td><td>{r.rim_diameter:.1f} {r.unit}</td></tr>
            <tr><td>Max Ø</td><td>{r.max_diameter:.1f} {r.unit}</td></tr>
            <tr><td>Preserved arc</td><td>{r.preserved_arc_deg:.0f}°</td></tr>
            <tr><td>Preserved height</td><td>{r.preserved_height:.1f} {r.unit}</td></tr>
            <tr><td>Fit RMSE</td><td>{r.fit_residual_rmse:.2f} {r.unit}</td></tr>
            <tr><td>Quality</td><td style="color:{quality_color};font-weight:bold">{r.quality.upper()}</td></tr>
          </table>
          {('<p class="notes">'+'<br>'.join(r.notes)+'</p>') if r.notes else ''}
        </div>""")

    html = f"""<!doctype html>
<html><head><meta charset="utf-8"><title>sherdtool report</title>
<style>
 body {{ font-family: -apple-system, system-ui, sans-serif; background:#f5f5f5; margin:0; padding:20px; color:#222 }}
 h1 {{ margin-bottom:0 }}
 .summary {{ color:#666; margin:4px 0 20px }}
 .grid {{ display:grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); gap:20px }}
 .sherd-card {{ background:white; border:1px solid #ddd; border-radius:6px; padding:14px }}
 .sherd-card h3 {{ margin:0 0 8px; font-family:monospace; font-size:13px }}
 .sherd-card img {{ width:100%; height:auto; display:block; background:#fafafa }}
 .sherd-card table {{ width:100%; margin-top:10px; font-size:12px; border-collapse:collapse }}
 .sherd-card td {{ padding:3px 6px; border-bottom:1px solid #eee }}
 .sherd-card td:first-child {{ color:#666; width:45% }}
 .notes {{ font-size:10px; color:#888; margin-top:6px; font-style:italic }}
 .no-img {{ background:#f0f0f0; padding:60px; text-align:center; color:#aaa }}
</style></head><body>
<h1>sherdtool — batch report</h1>
<p class="summary">{len(results)} sherd(s) processed.  Good: {sum(1 for r in results if r.quality=="good")}  ·  Warn: {sum(1 for r in results if r.quality=="warn")}  ·  Bad: {sum(1 for r in results if r.quality=="bad")}</p>
<div class="grid">{''.join(rows_html)}</div>
</body></html>"""
    (output_dir / "index.html").write_text(html, encoding="utf-8")


def batch_process(input_dir, output_dir, unit="auto", axis_hint="auto",
                  make_report=True, verbose=False, preview_only=False,
                  auto_orient=False, manual_rim_diameter=None,
                  extensions=(".obj", ".ply", ".stl", ".glb")):
    input_dir = Path(input_dir); output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    mesh_paths = sorted([p for p in input_dir.iterdir()
                         if p.suffix.lower() in extensions])
    if not mesh_paths:
        print(f"No mesh files found in {input_dir} (looking for {extensions})")
        return []

    print(f"Found {len(mesh_paths)} meshes in {input_dir}")
    results = []
    for i, mp in enumerate(mesh_paths, 1):
        print(f"[{i}/{len(mesh_paths)}] {mp.name}")
        try:
            r = process_one(mp, output_dir, unit=unit, axis_hint=axis_hint,
                            verbose=verbose, preview_only=preview_only,
                            auto_orient=auto_orient,
                            manual_rim_diameter=manual_rim_diameter)
            if isinstance(r, list):
                results.extend(r)
            else:
                results.append(r)
        except Exception as e:
            print(f"   [ERROR] {e}")
            if verbose: traceback.print_exc()
            r = SherdResult(name=mp.stem, path=str(mp), quality="bad",
                            notes=[f"exception: {e}"])
            results.append(r)

    if not preview_only:
        csv_path = output_dir / "summary.csv"
        write_csv(results, csv_path)
        print(f"\nWrote summary: {csv_path}")
        if make_report:
            write_html_gallery(results, output_dir)
            print(f"Wrote HTML gallery: {output_dir / 'index.html'}")
    return results


# =============================================================================
# CLI
# =============================================================================

def main():
    ap = argparse.ArgumentParser(
        description="Archaeological sherd analysis from 3D scans.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[1].split("Dependencies")[0])
    ap.add_argument("input", help="Mesh file or folder of meshes")
    ap.add_argument("-o", "--output", default="sherd_output",
                    help="Output directory (default: sherd_output)")
    ap.add_argument("--unit", choices=["mm", "cm", "m", "auto"], default="auto",
                    help="Mesh units (default: auto-detect; accepts mm/cm/m)")
    ap.add_argument("--axis", default="auto",
                    help="Approximate axis direction: X, Y, Z, auto, or 'dx,dy,dz' (default: auto)")
    ap.add_argument("--preview", action="store_true",
                    help="Only render 6-view preview, don't process")
    ap.add_argument("--interactive", action="store_true",
                    help="Interactive mode (single mesh only) with axis sliders")
    ap.add_argument("--no-report", action="store_true",
                    help="Skip HTML gallery report")
    ap.add_argument("--auto-orient", action="store_true",
                    help="Automatically detect vessel axis from rim geometry "
                         "and rotate the mesh to be rim-up before analysis. "
                         "Uses scientific rim plane fitting via boundary chains "
                         "+ multi-slice circle fits.")
    ap.add_argument("--rim-diameter", type=float, default=None,
                    help="Manual rim diameter in cm. Use this when the sherd "
                         "preserves only a small arc (<60°) and the geometric "
                         "rim radius estimate is unreliable. The pipeline will "
                         "use this value instead of measuring from geometry.")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    ip = Path(args.input)
    if not ip.exists():
        print(f"Error: {ip} not found"); sys.exit(1)

    if args.interactive:
        if ip.is_dir():
            print("--interactive requires a single file, not a folder"); sys.exit(1)
        interactive_mode(ip, args.output,
                         unit=(args.unit if args.unit != "auto" else "cm"),
                         verbose=args.verbose)
        return

    if ip.is_dir():
        batch_process(ip, args.output, unit=args.unit, axis_hint=args.axis,
                      make_report=not args.no_report, verbose=args.verbose,
                      preview_only=args.preview,
                      auto_orient=args.auto_orient,
                      manual_rim_diameter=args.rim_diameter)
    else:
        r = process_one(ip, args.output, unit=args.unit, axis_hint=args.axis,
                        verbose=args.verbose, preview_only=args.preview,
                        auto_orient=args.auto_orient,
                        manual_rim_diameter=args.rim_diameter)
        if not args.preview:
            results_list = r if isinstance(r, list) else [r]
            write_csv(results_list, Path(args.output) / "summary.csv")
            if not args.no_report:
                write_html_gallery(results_list, args.output)
        print(f"\nDone. Results in {args.output}")


if __name__ == "__main__":
    main()
