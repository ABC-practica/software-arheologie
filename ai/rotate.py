import argparse
import os
import sys
import numpy as np
import torch
from PIL import Image
from skimage.measure import label, regionprops, find_contours
from skimage.transform import resize

import torchvision
import matplotlib
import seaborn
import sklearn
import pandas
import cv2
import scipy
import tqdm

from models import CustomModel

if getattr(sys, 'frozen', False):
    BASE_DIR=os.path.dirname(sys.executable)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__)) if '__file__' in locals() else os.getcwd()

REGRESSOR_WEIGHTS= os.path.join(BASE_DIR, "regressor_model.pth")

parser = argparse.ArgumentParser(description="AI Pot Reconstruction")
parser.add_argument("--input", required=True,  help="Path to input folder")
parser.add_argument("--output", required=True, help="Path to output folder")
args=parser.parse_args()

SHARD_FOLDER=args.input
OUTPUT_DIR=args.output

os.makedirs(OUTPUT_DIR, exist_ok=True)
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Running solid AI-profile rotation on: {device}")

# ------------------------------------------------------------
# 2. LOAD REGRESSOR (FOR TRUE RADIUS & HEIGHT)
# ------------------------------------------------------------
regressor = CustomModel(num_classes=8).to(device)
regressor.load_state_dict(torch.load(REGRESSOR_WEIGHTS, map_location=device))
regressor.eval()


extensions = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff"}
shard_paths = sorted(
    [os.path.join(SHARD_FOLDER, f) for f in os.listdir(SHARD_FOLDER) if os.path.splitext(f)[1].lower() in extensions])

if not shard_paths:
    raise RuntimeError(f"No images in: {SHARD_FOLDER}")

img = Image.open(shard_paths[0]).convert("L")
if img.width > img.height:
    img = img.rotate(90, expand=True)

img_np = np.asarray(img).astype(np.float32) / 255.0
if img_np.mean() > 0.5:
    img_np = 1.0 - img_np

binary = (img_np > 0.5).astype(np.float32)
labeled = label(binary)
largest = max(regionprops(labeled), key=lambda r: r.area)


crop = binary[largest.bbox[0]:largest.bbox[2], largest.bbox[1]:largest.bbox[3]]


resized_for_ai = resize(crop, (128, 128), order=0, anti_aliasing=False)
raw_tensor = torch.from_numpy(resized_for_ai).unsqueeze(0).unsqueeze(0).to(device)
dummy_metadata = torch.zeros((1, 8, 128, 128), device=device, dtype=torch.float32)

regressor_input = torch.cat((raw_tensor, dummy_metadata), dim=1)

with torch.no_grad():
    pred = regressor(regressor_input).detach().cpu().numpy()[0]


pos_x = float(pred[0] * 128)
pos_y = float(pred[1] * 128)
scale = np.clip(float(pred[2] * 2.5), 0.3, 2.5) if len(pred) > 2 else 1.0

print(f"AI Predicted Canvas Placement -> X: {pos_x:.1f}, Y: {pos_y:.1f}, Scale: {scale:.2f}")

contours = find_contours(crop, 0.5)
contour = max(contours, key=len)

H, W = crop.shape
new_h, new_w = H * scale, W * scale

n_theta = 128
theta = np.linspace(0, 2 * np.pi, n_theta, endpoint=False)

vertices = []

for p in contour:
    y_pixel, x_pixel = p[0], p[1]


    mapped_y = y_pixel * (new_h / H) + (pos_y - new_h / 2.0)
    mapped_x = x_pixel * (new_w / W) + (pos_x - new_w / 2.0)


    r = abs(mapped_x - 64.0)
    z = 128.0 - mapped_y

    for t in theta:
        vertices.append([r * np.cos(t), z, r * np.sin(t)])

faces = []
N_points = len(contour)
for i in range(N_points):
    next_i = (i + 1) % N_points
    for j in range(n_theta):
        next_j = (j + 1) % n_theta

        a = i * n_theta + j
        b = i * n_theta + next_j
        c = next_i * n_theta + next_j
        d = next_i * n_theta + j
        faces.extend([[a, b, c], [a, c, d]])


OUTPUT_OBJ = os.path.join(OUTPUT_DIR, "ai_solid_proportioned_pot.obj")
with open(OUTPUT_OBJ, "w") as f:
    f.write("# Solid Proportioned Mesh (AI Placed)\n")
    for v in vertices:
        f.write(f"v {v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")
    for face in faces:
        f.write(f"f {face[0] + 1} {face[1] + 1} {face[2] + 1}\n")

print(f"Successfully generated solid 3D object: {OUTPUT_OBJ}")