import argparse
import os
import sys

import numpy as np
import pyvista as pv
import trimesh


DEFAULT_MESH_PATH = "shards/model.obj"

CANDIDATE_COUNT = 43
RANSAC_ITERATIONS = 900
MIN_SECTION_POINTS = 15
MIN_ARC_INLIERS = 10
MIN_SECTION_SEPARATION = 0.14
MAX_LOCAL_CANDIDATES = 14
AXIS_HYPOTHESIS_COUNT = 6500
MIN_MATCHED_SLICE_FRACTION = 0.34
AXIS_MATCH_DISTANCE_FRACTION = 0.055

MIN_SAGITTA_CHORD_RATIO = 0.008
MAX_SAGITTA_CHORD_RATIO = 0.22
MIN_RADIUS_TO_MESH_DIAGONAL = 0.28
MAX_RADIUS_TO_MESH_DIAGONAL = 3.0
MIN_VALID_RADIUS = 1e-5
LOCAL_INLIER_FRACTION = 0.0035
GLOBAL_FIT_ITERATIONS = 45

AXIS_ALIGNMENT_ITERATIONS = 2
AXIS_ALIGNMENT_TOLERANCE = np.deg2rad(0.15)

RNG = np.random.default_rng(20260917)


def normalize(vector):
    vector = np.asarray(vector, dtype=float)
    length = float(np.linalg.norm(vector))
    if length < 1e-12:
        return np.zeros_like(vector)
    return vector / length


def orthonormal_basis(normal):
    normal = normalize(normal)
    reference = np.array([0.0, 0.0, 1.0])
    if abs(float(np.dot(normal, reference))) > 0.9:
        reference = np.array([1.0, 0.0, 0.0])
    u = normalize(np.cross(normal, reference))
    v = normalize(np.cross(normal, u))
    return u, v


def project_points(points, origin, normal):
    points = np.asarray(points, dtype=float)
    origin = np.asarray(origin, dtype=float)
    u, v = orthonormal_basis(normal)
    relative = points - origin
    return np.column_stack((relative @ u, relative @ v)), u, v


def circle_from_three_points(points):
    p1, p2, p3 = np.asarray(points, dtype=float)
    matrix = 2.0 * np.array([p2 - p1, p3 - p1])
    rhs = np.array([
        float(np.dot(p2, p2) - np.dot(p1, p1)),
        float(np.dot(p3, p3) - np.dot(p1, p1)),
    ])
    determinant = float(np.linalg.det(matrix))
    if abs(determinant) < 1e-12:
        return None
    try:
        center = np.linalg.solve(matrix, rhs)
    except np.linalg.LinAlgError:
        return None
    radius = float(np.linalg.norm(p1 - center))
    if not np.isfinite(radius):
        return None
    return center, radius


def extract_section_samples(mesh, plane_origin, plane_normal):
    try:
        segments, face_indices = trimesh.intersections.mesh_plane(
            mesh,
            plane_normal=np.asarray(plane_normal, dtype=float),
            plane_origin=np.asarray(plane_origin, dtype=float),
            return_faces=True,
        )
        if segments is not None and len(segments) >= MIN_SECTION_POINTS:
            segments = np.asarray(segments, dtype=float)
            points = np.mean(segments, axis=1)
            normals = np.asarray(mesh.face_normals, dtype=float)[np.asarray(face_indices, dtype=int)]
            return points, normals
    except (ValueError, TypeError, np.linalg.LinAlgError):
        pass
    return None, None


def farthest_pair(points):
    points = np.asarray(points, dtype=float)
    if len(points) < 2:
        return None
    differences = points[:, None, :] - points[None, :, :]
    distances_squared = np.sum(differences * differences, axis=2)
    i, j = np.unravel_index(int(np.argmax(distances_squared)), distances_squared.shape)
    return points[i], points[j], float(np.sqrt(distances_squared[i, j]))


def arc_metrics(points, center, radius):
    pair = farthest_pair(points)
    if pair is None:
        return None
    endpoint_a, endpoint_b, chord = pair
    if chord <= 1e-10 or chord >= 2.0 * radius * (1.0 + 1e-6):
        return None

    half_chord = min(chord / (2.0 * radius), 1.0)
    sagitta = radius - np.sqrt(max(radius * radius - 0.25 * chord * chord, 0.0))
    ratio = float(sagitta / chord)
    arc_angle = float(2.0 * np.arcsin(half_chord))

    chord_midpoint = 0.5 * (endpoint_a + endpoint_b)
    arc_mean = np.mean(points, axis=0)
    opposite_side = float(np.dot(center - chord_midpoint, arc_mean - chord_midpoint)) < 0.0
    return chord, sagitta, ratio, arc_angle, opposite_side


def robust_scale(values, floor):
    values = np.asarray(values, dtype=float)
    median = float(np.median(values))
    mad = float(np.median(np.abs(values - median)))
    return max(1.4826 * mad, floor)


def ransac_arc_candidates(points_2d, normals_2d, mesh_diagonal):
    points_2d = np.asarray(points_2d, dtype=float)
    normals_2d = np.asarray(normals_2d, dtype=float)
    normal_lengths = np.linalg.norm(normals_2d, axis=1)
    usable = normal_lengths >= 0.35
    points_2d = points_2d[usable]
    normals_2d = normals_2d[usable] / normal_lengths[usable, None]
    if len(points_2d) < MIN_SECTION_POINTS:
        return []

    base_tolerance = max(mesh_diagonal * LOCAL_INLIER_FRACTION, 1e-5)
    minimum_radius = mesh_diagonal * MIN_RADIUS_TO_MESH_DIAGONAL
    maximum_radius = mesh_diagonal * MAX_RADIUS_TO_MESH_DIAGONAL
    candidates = []

    for _ in range(RANSAC_ITERATIONS):
        sample_indices = RNG.choice(len(points_2d), size=3, replace=False)
        result = circle_from_three_points(points_2d[sample_indices])
        if result is None:
            continue
        center, radius = result
        if radius < minimum_radius or radius > maximum_radius:
            continue

        center_vectors = center - points_2d
        center_distances = np.linalg.norm(center_vectors, axis=1)
        radial_errors = np.abs(center_distances - radius)
        preliminary = radial_errors <= base_tolerance
        if int(np.count_nonzero(preliminary)) < MIN_ARC_INLIERS:
            continue

        local_scale = robust_scale(radial_errors[preliminary], base_tolerance * 0.35)
        tolerance = min(max(2.5 * local_scale, base_tolerance * 0.65), base_tolerance * 2.5)
        radial_directions = center_vectors / np.maximum(center_distances[:, None], 1e-12)
        normal_alignment = np.einsum("ij,ij->i", normals_2d, radial_directions)
        inliers = (radial_errors <= tolerance) & (normal_alignment >= 0.20)
        count = int(np.count_nonzero(inliers))
        if count < MIN_ARC_INLIERS:
            continue
        if float(np.median(normal_alignment[inliers])) < 0.35:
            continue

        inlier_points = points_2d[inliers]
        metrics = arc_metrics(inlier_points, center, radius)
        if metrics is None:
            continue
        chord, sagitta, sagitta_ratio, arc_angle, opposite_side = metrics
        if not opposite_side:
            continue
        if not (MIN_SAGITTA_CHORD_RATIO <= sagitta_ratio <= MAX_SAGITTA_CHORD_RATIO):
            continue
        if arc_angle >= np.pi * 0.98:
            continue

        residual = float(np.median(radial_errors[inliers]))
        alignment = float(np.median(normal_alignment[inliers]))
        support = count / float(len(points_2d))
        quality = count * support * alignment
        quality *= np.sqrt(max(chord / mesh_diagonal, 1e-8))
        quality /= 1.0 + residual / base_tolerance
        candidates.append({
            "center_2d": center,
            "radius": radius,
            "residual": residual,
            "inlier_mask": inliers,
            "inlier_count": count,
            "chord": chord,
            "sagitta": sagitta,
            "sagitta_ratio": sagitta_ratio,
            "arc_angle": arc_angle,
            "normal_alignment": alignment,
            "quality": float(quality),
        })

    candidates.sort(key=lambda item: item["quality"], reverse=True)
    unique = []
    center_tolerance = max(mesh_diagonal * 0.018, 1e-5)
    radius_tolerance = max(mesh_diagonal * 0.015, 1e-5)
    for candidate in candidates:
        duplicate = any(
            np.linalg.norm(candidate["center_2d"] - kept["center_2d"]) <= center_tolerance
            and abs(candidate["radius"] - kept["radius"]) <= radius_tolerance
            for kept in unique
        )
        if not duplicate:
            unique.append(candidate)
        if len(unique) >= MAX_LOCAL_CANDIDATES:
            break
    return unique


def fit_section_candidates(
    mesh,
    coordinate,
    normal,
    reference_origin,
    mesh_diagonal,
):
    normal = normalize(normal)
    plane_origin = np.asarray(reference_origin, dtype=float) + float(coordinate) * normal
    points_3d, face_normals = extract_section_samples(mesh, plane_origin, normal)
    if points_3d is None or len(points_3d) < MIN_SECTION_POINTS:
        return []

    points_2d, u, v = project_points(points_3d, plane_origin, normal)
    normals_2d = np.column_stack((face_normals @ u, face_normals @ v))
    usable = np.linalg.norm(normals_2d, axis=1) >= 0.35
    usable_points_3d = points_3d[usable]
    candidates = ransac_arc_candidates(points_2d, normals_2d, mesh_diagonal)
    results = []
    for selected in candidates:
        center_2d = selected["center_2d"]
        center_3d = plane_origin + center_2d[0] * u + center_2d[1] * v
        inner_points = usable_points_3d[selected["inlier_mask"]]
        inner_radius = float(selected["radius"])
        results.append({
            "coordinate": float(coordinate),
            "z": float(center_3d[2]),
            "center": center_3d,
            "inner_radius": inner_radius,
            "outer_radius": inner_radius,
            "radius": inner_radius,
            "residual": float(selected["residual"]),
            "quality": float(selected["quality"]),
            "normal_alignment": float(selected["normal_alignment"]),
            "chord": float(selected["chord"]),
            "sagitta": float(selected["sagitta"]),
            "sagitta_ratio": float(selected["sagitta_ratio"]),
            "arc_angle": float(selected["arc_angle"]),
            "points": points_3d,
            "inner_points": inner_points,
            "is_fallback": False,
        })
    return results


def candidate_slice_normals(mesh):
    vertices = np.asarray(mesh.vertices, dtype=float)
    centered = vertices - np.mean(vertices, axis=0)
    covariance = centered.T @ centered / max(len(vertices), 1)
    _, eigenvectors = np.linalg.eigh(covariance)
    normals = [np.array([0.0, 0.0, 1.0])]
    normals.extend(eigenvectors[:, index] for index in range(3))
    unique = []
    for normal in normals:
        normal = normalize(normal)
        if normal[2] < 0.0:
            normal = -normal
        if not any(abs(float(np.dot(normal, existing))) > 0.985 for existing in unique):
            unique.append(normal)
    return unique


def find_candidate_pools(mesh, normal, mesh_diagonal):
    vertices = np.asarray(mesh.vertices, dtype=float)
    reference_origin = np.mean(vertices, axis=0)
    coordinates = (vertices - reference_origin) @ normal
    low, high = float(np.min(coordinates)), float(np.max(coordinates))
    span = high - low
    if span <= 1e-8:
        return [], reference_origin, low, high
    margin = 0.06 * span
    samples = np.linspace(low + margin, high - margin, CANDIDATE_COUNT)
    pools = []
    for coordinate in samples:
        candidates = fit_section_candidates(
            mesh, coordinate, normal, reference_origin, mesh_diagonal
        )
        if candidates:
            pools.append({"coordinate": float(coordinate), "candidates": candidates})
    return pools, reference_origin, low, high


def fit_line_pca(centers, preferred_direction=None):
    centers = np.asarray(centers, dtype=float)
    if len(centers) < 2:
        return None
    origin = np.mean(centers, axis=0)
    try:
        _, _, vh = np.linalg.svd(centers - origin, full_matrices=False)
    except np.linalg.LinAlgError:
        return None
    direction = normalize(vh[0])
    if preferred_direction is not None and np.dot(direction, preferred_direction) < 0.0:
        direction = -direction
    elif preferred_direction is None and direction[2] < 0.0:
        direction = -direction
    return origin, direction


def point_line_distance(point, origin, direction):
    relative = np.asarray(point, dtype=float) - np.asarray(origin, dtype=float)
    return float(np.linalg.norm(relative - np.dot(relative, direction) * direction))


def matched_candidates_for_axis(pools, origin, direction, mesh_diagonal):
    threshold = mesh_diagonal * AXIS_MATCH_DISTANCE_FRACTION
    matches = []
    distances = []
    for pool in pools:
        candidates = pool["candidates"]
        local_distances = [
            point_line_distance(candidate["center"], origin, direction)
            for candidate in candidates
        ]
        best_index = int(np.argmin(local_distances))
        if local_distances[best_index] <= threshold:
            matches.append(candidates[best_index])
            distances.append(local_distances[best_index])
    return matches, np.asarray(distances, dtype=float)


def estimate_axis_from_candidate_pools(pools, preferred_direction, mesh_diagonal):
    if len(pools) < 3:
        return None
    coordinate_span = pools[-1]["coordinate"] - pools[0]["coordinate"]
    eligible_pairs = []
    for first in range(len(pools)):
        for second in range(first + 1, len(pools)):
            separation = pools[second]["coordinate"] - pools[first]["coordinate"]
            if separation >= 0.38 * coordinate_span:
                eligible_pairs.append((first, second))
    if not eligible_pairs:
        return None

    best = None
    attempts = min(AXIS_HYPOTHESIS_COUNT, max(1200, len(eligible_pairs) * 20))
    for _ in range(attempts):
        first_index, second_index = eligible_pairs[int(RNG.integers(len(eligible_pairs)))]
        first_pool = pools[first_index]["candidates"]
        second_pool = pools[second_index]["candidates"]
        first = first_pool[int(RNG.integers(len(first_pool)))]
        second = second_pool[int(RNG.integers(len(second_pool)))]
        direction = normalize(second["center"] - first["center"])
        if np.linalg.norm(direction) < 1e-12:
            continue
        alignment = abs(float(np.dot(direction, preferred_direction)))
        if alignment < 0.55:
            continue
        if np.dot(direction, preferred_direction) < 0.0:
            direction = -direction
        origin = 0.5 * (first["center"] + second["center"])
        matches, distances = matched_candidates_for_axis(
            pools, origin, direction, mesh_diagonal
        )
        minimum_matches = max(6, int(np.ceil(MIN_MATCHED_SLICE_FRACTION * len(pools))))
        if len(matches) < minimum_matches:
            continue
        ordered = sorted(matches, key=lambda item: item["coordinate"])
        radii = np.asarray([item["inner_radius"] for item in ordered], dtype=float)
        radius_roughness = (
            float(np.median(np.abs(np.diff(radii, n=2)))) if len(radii) >= 3 else 0.0
        )
        mean_quality = float(np.mean([np.log1p(item["quality"]) for item in matches]))
        score = 12.0 * len(matches)
        score += 2.0 * mean_quality
        score -= 3.0 * float(np.median(distances)) / max(mesh_diagonal, 1e-8)
        score -= 0.04 * radius_roughness
        if best is None or score > best[0]:
            best = (score, origin, direction, matches, distances)

    if best is None:
        return None

    score, origin, direction, _, _ = best
    for _ in range(3):
        matches, distances = matched_candidates_for_axis(
            pools, origin, direction, mesh_diagonal
        )
        line = fit_line_pca(
            np.asarray([candidate["center"] for candidate in matches]),
            preferred_direction,
        )
        if line is None:
            break
        origin, direction = line
    matches, distances = matched_candidates_for_axis(
        pools, origin, direction, mesh_diagonal
    )
    return {
        "score": float(score),
        "origin": origin,
        "direction": direction,
        "matches": matches,
        "distances": distances,
    }


def choose_three_axis_sections(axis_solution, mesh_diagonal):
    matches = sorted(axis_solution["matches"], key=lambda item: item["coordinate"])
    if len(matches) < 3:
        return None
    coordinates = np.asarray([item["coordinate"] for item in matches], dtype=float)
    low, high = float(coordinates[0]), float(coordinates[-1])
    span = high - low
    targets = [low + 0.16 * span, low + 0.50 * span, low + 0.84 * span]
    selected = []
    for target in targets:
        window = max(0.14 * span, 1e-8)
        available = [
            item for item in matches
            if abs(item["coordinate"] - target) <= window and item not in selected
        ]
        if not available:
            available = [item for item in matches if item not in selected]
        chosen = max(
            available,
            key=lambda item: (
                np.log1p(item["quality"])
                - 4.0 * point_line_distance(
                    item["center"], axis_solution["origin"], axis_solution["direction"]
                ) / max(mesh_diagonal, 1e-8)
                - abs(item["coordinate"] - target) / max(span, 1e-8)
            ),
        )
        selected.append(dict(chosen))
    selected.sort(key=lambda item: item["coordinate"], reverse=True)
    return selected


def canonical_axis_parameters(parameters):
    parameters = np.asarray(parameters, dtype=float).copy()
    direction = normalize(parameters[3:6])
    if np.linalg.norm(direction) < 1e-12:
        direction = np.array([0.0, 0.0, 1.0])
    parameters[3:6] = direction
    mean_t = float(np.mean(parameters[6:9]))
    parameters[:3] += mean_t * direction
    parameters[6:9] -= mean_t
    return parameters


def unpack_global_parameters(parameters):
    parameters = canonical_axis_parameters(parameters)
    origin = parameters[:3]
    direction = parameters[3:6]
    axial_positions = parameters[6:9]
    radii = parameters[9:12]
    centers = origin + axial_positions[:, None] * direction
    return origin, direction, axial_positions, radii, centers


def global_residual_vector(parameters, point_sets, initial_centers, mesh_diagonal):
    origin, direction, _, radii, centers = unpack_global_parameters(parameters)
    scale = max(mesh_diagonal * 0.003, 1e-6)
    residuals = []
    for index, points in enumerate(point_sets):
        points = np.asarray(points, dtype=float)
        if len(points) > 250:
            indices = np.linspace(0, len(points) - 1, 250).astype(int)
            points = points[indices]
        relative = points - centers[index]
        axial_offsets = relative @ direction
        distances = np.linalg.norm(relative, axis=1)
        balance = scale * np.sqrt(max(len(points), 1))
        residuals.append((distances - radii[index]) / balance)
        residuals.append(axial_offsets / balance)

    data_residuals = np.concatenate(residuals)
    center_regularizer = 0.003 * ((centers - initial_centers) / max(mesh_diagonal, 1e-8)).ravel()
    radius_penalty = np.maximum(MIN_VALID_RADIUS - radii, 0.0) / scale
    direction_penalty = np.array([(np.linalg.norm(direction) - 1.0) * 0.1])
    return np.concatenate((data_residuals, center_regularizer, radius_penalty, direction_penalty))


def numerical_jacobian(function, parameters, base_residual, mesh_diagonal):
    jacobian = np.empty((len(base_residual), len(parameters)), dtype=float)
    for column in range(len(parameters)):
        step = 1e-5 * max(abs(parameters[column]), mesh_diagonal * 0.05, 1.0)
        changed = parameters.copy()
        changed[column] += step
        jacobian[:, column] = (function(changed) - base_residual) / step
    return jacobian


def global_axis_circle_fit(sections, mesh_diagonal, thickness, preferred_direction=None):
    initial_centers = np.asarray([section["center"] for section in sections], dtype=float)
    initial_line = fit_line_pca(initial_centers, preferred_direction)
    if initial_line is None:
        raise RuntimeError("Centrele inițiale nu permit estimarea unei axe.")
    origin, direction = initial_line
    axial_positions = (initial_centers - origin) @ direction
    radii = np.asarray([section["inner_radius"] for section in sections], dtype=float)
    parameters = np.concatenate((origin, direction, axial_positions, radii))
    point_sets = [np.asarray(section["inner_points"], dtype=float) for section in sections]

    def residual_function(values):
        return global_residual_vector(values, point_sets, initial_centers, mesh_diagonal)

    damping = 1e-2
    current_residual = residual_function(parameters)
    current_cost = float(np.dot(current_residual, current_residual))

    for _ in range(GLOBAL_FIT_ITERATIONS):
        absolute = np.abs(current_residual)
        huber_limit = max(1.5, 2.5 * float(np.median(absolute)))
        weights = np.ones_like(current_residual)
        large = absolute > huber_limit
        weights[large] = huber_limit / np.maximum(absolute[large], 1e-12)
        square_root_weights = np.sqrt(weights)

        jacobian = numerical_jacobian(residual_function, parameters, current_residual, mesh_diagonal)
        weighted_jacobian = jacobian * square_root_weights[:, None]
        weighted_residual = current_residual * square_root_weights
        normal_matrix = weighted_jacobian.T @ weighted_jacobian
        gradient = weighted_jacobian.T @ weighted_residual
        diagonal = np.maximum(np.diag(normal_matrix), 1e-8)
        try:
            delta = np.linalg.solve(normal_matrix + damping * np.diag(diagonal), -gradient)
        except np.linalg.LinAlgError:
            delta = np.linalg.lstsq(normal_matrix + damping * np.eye(len(parameters)), -gradient, rcond=None)[0]

        trial = canonical_axis_parameters(parameters + delta)
        trial[9:12] = np.clip(
            trial[9:12],
            MIN_VALID_RADIUS,
            mesh_diagonal * MAX_RADIUS_TO_MESH_DIAGONAL,
        )
        trial_residual = residual_function(trial)
        trial_cost = float(np.dot(trial_residual, trial_residual))
        if trial_cost < current_cost:
            relative_improvement = (current_cost - trial_cost) / max(current_cost, 1e-12)
            parameters = trial
            current_residual = trial_residual
            current_cost = trial_cost
            damping = max(damping * 0.45, 1e-7)
            if relative_improvement < 1e-7:
                break
        else:
            damping = min(damping * 5.0, 1e8)

    origin, direction, axial_positions, radii, centers = unpack_global_parameters(parameters)
    if preferred_direction is not None and np.dot(direction, preferred_direction) < 0.0:
        direction = -direction
        axial_positions = -axial_positions

    fitted_sections = []
    raw_errors = []
    for index, section in enumerate(sections):
        updated = dict(section)
        updated["center"] = centers[index]
        updated["inner_radius"] = float(radii[index])
        updated["outer_radius"] = float(radii[index] + thickness)
        updated["radius"] = float(radii[index])
        distances = np.linalg.norm(point_sets[index] - centers[index], axis=1)
        raw_errors.extend(np.abs(distances - radii[index]))
        fitted_sections.append(updated)
    rms = float(np.sqrt(np.mean(np.square(raw_errors)))) if raw_errors else np.inf
    return fitted_sections, origin, normalize(direction), rms


def rotation_matrix_from_vectors(source, target):
    source, target = normalize(source), normalize(target)
    cross = np.cross(source, target)
    dot = float(np.clip(np.dot(source, target), -1.0, 1.0))
    cross_length = float(np.linalg.norm(cross))
    if cross_length < 1e-12:
        if dot > 0.0:
            return np.eye(3)
        reference = np.array([1.0, 0.0, 0.0])
        if abs(float(np.dot(source, reference))) > 0.9:
            reference = np.array([0.0, 1.0, 0.0])
        axis = normalize(np.cross(source, reference))
        skew = np.array([
            [0.0, -axis[2], axis[1]],
            [axis[2], 0.0, -axis[0]],
            [-axis[1], axis[0], 0.0],
        ])
        return np.eye(3) + 2.0 * (skew @ skew)
    skew = np.array([
        [0.0, -cross[2], cross[1]],
        [cross[2], 0.0, -cross[0]],
        [-cross[1], cross[0], 0.0],
    ])
    return np.eye(3) + skew + (skew @ skew) * ((1.0 - dot) / (cross_length * cross_length))


def rotate_points(points, rotation, origin):
    return (np.asarray(points, dtype=float) - origin) @ rotation.T + origin


def rotate_mesh(mesh, rotation, origin):
    transformed = mesh.copy()
    transformed.vertices = rotate_points(mesh.vertices, rotation, origin)
    return transformed


def rotate_sections(sections, rotation, origin):
    rotated = []
    for section in sections:
        updated = dict(section)
        updated["center"] = rotate_points(np.asarray([section["center"]]), rotation, origin)[0]
        updated["points"] = rotate_points(section["points"], rotation, origin)
        updated["inner_points"] = rotate_points(section["inner_points"], rotation, origin)
        updated["z"] = float(updated["center"][2])
        rotated.append(updated)
    return rotated


def find_best_axis_solution(mesh, normals, mesh_diagonal):
    trials = []
    for normal in normals:
        normal = normalize(normal)
        pools, _, _, _ = find_candidate_pools(mesh, normal, mesh_diagonal)
        solution = estimate_axis_from_candidate_pools(pools, normal, mesh_diagonal)
        if solution is None:
            continue
        solution["pools"] = pools
        match_fraction = len(solution["matches"]) / max(len(pools), 1)
        median_distance = (
            float(np.median(solution["distances"])) if len(solution["distances"]) else np.inf
        )
        comparison_score = 100.0 * match_fraction
        comparison_score -= 10.0 * median_distance / max(mesh_diagonal, 1e-8)
        comparison_score += 0.02 * solution["score"] / max(len(pools), 1)
        trials.append((comparison_score, solution))
    if not trials:
        return None
    return max(trials, key=lambda item: item[0])[1]


def build_automatic_solution(mesh, thickness):
    vertices = np.asarray(mesh.vertices, dtype=float)
    mesh_diagonal = float(np.linalg.norm(vertices.max(axis=0) - vertices.min(axis=0)))
    if mesh_diagonal <= 1e-10:
        raise RuntimeError("Mesh-ul nu are dimensiuni geometrice valide.")

    initial_solution = find_best_axis_solution(
        mesh, candidate_slice_normals(mesh), mesh_diagonal
    )
    if initial_solution is None:
        raise RuntimeError(
            "Nu a putut fi estimată o axă robustă din arcele suprafeței interioare. "
            "Nu s-au folosit cercuri fallback."
        )
    vertical = np.array([0.0, 0.0, 1.0])
    initial_origin = initial_solution["origin"]
    initial_direction = initial_solution["direction"]
    if np.dot(initial_direction, vertical) < 0.0:
        initial_direction = -initial_direction
    initial_rotation = rotation_matrix_from_vectors(initial_direction, vertical)
    current_mesh = rotate_mesh(mesh, initial_rotation, initial_origin)

    final_sections = None
    final_origin = initial_origin.copy()
    final_direction = vertical.copy()
    for iteration in range(AXIS_ALIGNMENT_ITERATIONS + 1):
        solution = find_best_axis_solution(current_mesh, [vertical], mesh_diagonal)
        if solution is None:
            raise RuntimeError(
                "După aliniere nu au putut fi găsite suficiente arce interioare coerente."
            )
        selected = choose_three_axis_sections(solution, mesh_diagonal)
        if selected is None:
            raise RuntimeError("Nu au putut fi selectate trei secțiuni finale distincte.")
        for section in selected:
            section["outer_radius"] = section["inner_radius"] + thickness
            section["radius"] = section["inner_radius"]

        final_sections, final_origin, final_direction, _ = global_axis_circle_fit(
            selected,
            mesh_diagonal,
            thickness,
            preferred_direction=vertical,
        )
        if np.dot(final_direction, vertical) < 0.0:
            final_direction = -final_direction
        angle = float(
            np.arccos(np.clip(float(np.dot(final_direction, vertical)), -1.0, 1.0))
        )
        if angle <= AXIS_ALIGNMENT_TOLERANCE or iteration == AXIS_ALIGNMENT_ITERATIONS:
            break
        rotation = rotation_matrix_from_vectors(final_direction, vertical)
        current_mesh = rotate_mesh(current_mesh, rotation, final_origin)

    final_sections.sort(
        key=lambda section: np.dot(section["center"], final_direction), reverse=True
    )
    final_rotation = rotation_matrix_from_vectors(final_direction, vertical)
    current_mesh = rotate_mesh(current_mesh, final_rotation, final_origin)
    final_sections = rotate_sections(final_sections, final_rotation, final_origin)
    final_sections.sort(key=lambda section: section["center"][2], reverse=True)
    return current_mesh, final_sections, final_origin, vertical.copy(), mesh_diagonal


def robust_radius_from_center(inner_points, center):
    distances = np.linalg.norm(np.asarray(inner_points, dtype=float) - np.asarray(center, dtype=float), axis=1)
    if not len(distances):
        return None
    median = float(np.median(distances))
    scale = robust_scale(distances, max(median * 1e-4, 1e-8))
    kept = distances[np.abs(distances - median) <= 2.5 * scale]
    if not len(kept):
        kept = distances
    return float(np.median(kept))


def anchored_axis_from_centers(centers, anchor_index, previous_direction):
    centers = np.asarray(centers, dtype=float)
    anchor = centers[anchor_index]
    vectors = centers - anchor
    try:
        _, _, vh = np.linalg.svd(vectors, full_matrices=False)
        direction = normalize(vh[0])
    except np.linalg.LinAlgError:
        direction = normalize(previous_direction)
    if np.dot(direction, previous_direction) < 0.0:
        direction = -direction
    projected = np.empty_like(centers)
    for index, center in enumerate(centers):
        if index == anchor_index:
            projected[index] = anchor
        else:
            projected[index] = anchor + np.dot(center - anchor, direction) * direction
    origin = np.mean(projected, axis=0)
    return projected, origin, direction


def create_circle(center, normal, radius, segments=120):
    center = np.asarray(center, dtype=float)
    u, v = orthonormal_basis(normal)
    angles = np.linspace(0.0, 2.0 * np.pi, segments, endpoint=False)
    return center + radius * (
        np.cos(angles)[:, None] * u + np.sin(angles)[:, None] * v
    )


def loft_surface_between_circles(sections, axis_direction, radial_segments=72, segments_per_pair=18):
    axis_direction = normalize(axis_direction)
    ordered = sorted(sections, key=lambda section: np.dot(section["center"], axis_direction))
    if len(ordered) < 2:
        return None

    ring_centers = []
    inner_radii = []
    outer_radii = []
    for index in range(len(ordered) - 1):
        first, second = ordered[index], ordered[index + 1]
        steps = segments_per_pair + (1 if index == len(ordered) - 2 else 0)
        for step in range(steps):
            interpolation = step / float(segments_per_pair)
            ring_centers.append(
                first["center"] * (1.0 - interpolation) + second["center"] * interpolation
            )
            inner_radii.append(
                first["inner_radius"] * (1.0 - interpolation)
                + second["inner_radius"] * interpolation
            )
            outer_radii.append(
                first["outer_radius"] * (1.0 - interpolation)
                + second["outer_radius"] * interpolation
            )

    u, v = orthonormal_basis(axis_direction)
    angles = np.linspace(0.0, 2.0 * np.pi, radial_segments, endpoint=False)
    outer_vertices = []
    inner_vertices = []
    for center, inner_radius, outer_radius in zip(
        ring_centers, inner_radii, outer_radii
    ):
        outer_vertices.extend(
            center + outer_radius * (np.cos(angle) * u + np.sin(angle) * v)
            for angle in angles
        )
        inner_vertices.extend(
            center + inner_radius * (np.cos(angle) * u + np.sin(angle) * v)
            for angle in angles
        )
    vertices = np.asarray(outer_vertices + inner_vertices, dtype=float)

    faces = []
    ring_count = len(ring_centers)
    surface_vertex_count = ring_count * radial_segments
    for ring in range(ring_count - 1):
        for angle_index in range(radial_segments):
            next_index = (angle_index + 1) % radial_segments
            outer_a = ring * radial_segments + angle_index
            outer_b = ring * radial_segments + next_index
            outer_c = (ring + 1) * radial_segments + angle_index
            outer_d = (ring + 1) * radial_segments + next_index
            faces.extend(
                ([outer_a, outer_b, outer_c], [outer_b, outer_d, outer_c])
            )

            inner_a = surface_vertex_count + outer_a
            inner_b = surface_vertex_count + outer_b
            inner_c = surface_vertex_count + outer_c
            inner_d = surface_vertex_count + outer_d
            faces.extend(
                ([inner_a, inner_c, inner_b], [inner_b, inner_c, inner_d])
            )

    first_outer = 0
    first_inner = surface_vertex_count
    last_outer = (ring_count - 1) * radial_segments
    last_inner = surface_vertex_count + last_outer
    for angle_index in range(radial_segments):
        next_index = (angle_index + 1) % radial_segments

        lower_outer_a = first_outer + angle_index
        lower_outer_b = first_outer + next_index
        lower_inner_a = first_inner + angle_index
        lower_inner_b = first_inner + next_index
        faces.extend(
            (
                [lower_outer_a, lower_inner_a, lower_outer_b],
                [lower_outer_b, lower_inner_a, lower_inner_b],
            )
        )

        upper_outer_a = last_outer + angle_index
        upper_outer_b = last_outer + next_index
        upper_inner_a = last_inner + angle_index
        upper_inner_b = last_inner + next_index
        faces.extend(
            (
                [upper_outer_a, upper_outer_b, upper_inner_a],
                [upper_outer_b, upper_inner_b, upper_inner_a],
            )
        )
    return vertices, np.asarray(faces, dtype=int)


def trimesh_to_pyvista(mesh):
    faces = np.empty((len(mesh.faces), 4), dtype=int)
    faces[:, 0] = 3
    faces[:, 1:] = mesh.faces
    return pv.PolyData(np.asarray(mesh.vertices), faces.ravel())


def generate_pv_circle(center, normal, radius, mesh_size):
    points = create_circle(center, normal, radius)
    closed = np.vstack((points, points[0]))
    tube_radius = max(mesh_size * 0.003, 1e-5)
    return pv.Spline(closed).tube(radius=tube_radius)


def generate_pv_loft(sections, axis_direction):
    result = loft_surface_between_circles(sections, axis_direction)
    if result is None:
        return pv.PolyData()
    vertices, triangles = result
    faces = np.empty((len(triangles), 4), dtype=int)
    faces[:, 0] = 3
    faces[:, 1:] = triangles
    return pv.PolyData(vertices, faces.ravel())


class InteractiveArheoAxis:
    def __init__(self, mesh_path, thickness):
        self._ready = False
        self._updating_widgets = False
        self.thickness = float(thickness)
        if self.thickness <= 0.0:
            raise ValueError("Grosimea trebuie să fie strict pozitivă.")

        print("Analizare: arce interioare, fit global al axei și aliniere rigidă...")
        loaded = trimesh.load(mesh_path, force="mesh")
        if isinstance(loaded, trimesh.Scene):
            loaded = trimesh.util.concatenate(tuple(loaded.geometry.values()))
        if not isinstance(loaded, trimesh.Trimesh) or len(loaded.vertices) < 3:
            raise RuntimeError("Fișierul nu conține un mesh triunghiular valid.")

        (
            self.mesh,
            self.sections,
            self.axis_origin,
            self.axis_dir,
            self.mesh_size,
        ) = build_automatic_solution(loaded, self.thickness)
        self.axis_length = max(self.mesh_size * 2.0, 1.0)

        for index, section in enumerate(self.sections, start=1):
            print(
                f"Secțiunea {index}: r_inner={section['inner_radius']:.6g}, "
                f"r_outer={section['outer_radius']:.6g}, "
                f"sagitta/chord={section.get('sagitta_ratio', float('nan')):.5f}, "
                f"reziduu={section.get('residual', float('nan')):.6g}"
            )

        self.plotter = pv.Plotter(title="Arheo-Axis — axa globală a vasului")
        self.pv_mesh = trimesh_to_pyvista(self.mesh)
        self.plotter.add_mesh(
            self.pv_mesh, color="#b98455", opacity=0.90, smooth_shading=True, name="shard"
        )

        self.loft_mesh = generate_pv_loft(self.sections, self.axis_dir)
        self.plotter.add_mesh(
            self.loft_mesh, color="lightgray", opacity=0.35, show_edges=False, name="loft"
        )
        self.axis_mesh = pv.Line(
            self.axis_origin - self.axis_dir * self.axis_length / 2.0,
            self.axis_origin + self.axis_dir * self.axis_length / 2.0,
        )
        self.plotter.add_mesh(self.axis_mesh, color="white", line_width=4, name="axis_line")

        self.circle_meshes = []
        self.center_widgets = []
        self.radius_widgets = []
        labels = ["Superior (Roșu)", "Median (Verde)", "Inferior (Albastru)"]
        colors = ["red", "green", "blue"]

        for index, section in enumerate(self.sections):
            circle = generate_pv_circle(
                section["center"], self.axis_dir, section["inner_radius"], self.mesh_size
            )
            self.circle_meshes.append(circle)
            self.plotter.add_mesh(circle, color=colors[index], name=f"circle_{index}")

            center_widget = self.plotter.add_sphere_widget(
                self.make_center_callback(index),
                center=section["center"],
                radius=self.mesh_size * 0.02,
                color=colors[index],
                test_callback=False,
            )
            self.center_widgets.append(center_widget)

            radius = section["inner_radius"]
            slider_widget = self.plotter.add_slider_widget(
                self.make_radius_callback(index),
                rng=[max(radius * 0.15, MIN_VALID_RADIUS), max(radius * 4.0, self.mesh_size * 2.0)],
                value=radius,
                title=f"Raza interioară {labels[index]}",
                pointa=(0.02, 0.90 - index * 0.12),
                pointb=(0.29, 0.90 - index * 0.12),
                style="modern",
            )
            self.radius_widgets.append(slider_widget)

        self.plotter.add_text(
            f"Grosime: {self.thickness:g} | cercuri colorate = interior | loft gri = exterior",
            position="lower_left",
            font_size=10,
            name="geometry_info",
        )
        self._ready = True
        self.plotter.add_axes()
        self.plotter.show()

    def make_center_callback(self, index):
        def callback(new_center):
            if not self._ready or self._updating_widgets:
                return
            current_centers = np.asarray([section["center"] for section in self.sections], dtype=float)
            current_centers[index] = np.asarray(new_center, dtype=float)
            projected, new_origin, new_direction = anchored_axis_from_centers(
                current_centers, index, self.axis_dir
            )

            self.axis_origin = new_origin
            self.axis_dir = new_direction
            for section_index, section in enumerate(self.sections):
                center_changed = np.linalg.norm(section["center"] - projected[section_index]) > 1e-10
                section["center"] = projected[section_index]
                if section_index == index or center_changed:
                    new_radius = robust_radius_from_center(section["inner_points"], section["center"])
                    if new_radius is not None and np.isfinite(new_radius):
                        section["inner_radius"] = max(new_radius, MIN_VALID_RADIUS)
                        section["radius"] = section["inner_radius"]
                section["outer_radius"] = section["inner_radius"] + self.thickness

            self.update_geometry(update_widgets=True)

        return callback

    def make_radius_callback(self, index):
        def callback(new_radius):
            if not self._ready or self._updating_widgets:
                return
            inner_radius = max(float(new_radius), MIN_VALID_RADIUS)
            self.sections[index]["inner_radius"] = inner_radius
            self.sections[index]["radius"] = inner_radius
            self.sections[index]["outer_radius"] = inner_radius + self.thickness
            self.update_geometry(update_widgets=False)

        return callback

    def update_geometry(self, update_widgets):
        new_axis = pv.Line(
            self.axis_origin - self.axis_dir * self.axis_length / 2.0,
            self.axis_origin + self.axis_dir * self.axis_length / 2.0,
        )
        self.axis_mesh.copy_from(new_axis)

        for index, section in enumerate(self.sections):
            new_circle = generate_pv_circle(
                section["center"], self.axis_dir, section["inner_radius"], self.mesh_size
            )
            self.circle_meshes[index].copy_from(new_circle)

        self.loft_mesh.copy_from(generate_pv_loft(self.sections, self.axis_dir))

        if update_widgets:
            self._updating_widgets = True
            try:
                for index, section in enumerate(self.sections):
                    widget = self.center_widgets[index]
                    if isinstance(widget, (list, tuple)):
                        widget = widget[0]
                    widget.SetCenter(section["center"])
                    slider = self.radius_widgets[index]
                    representation = slider.GetRepresentation()
                    radius = section["inner_radius"]
                    if radius < representation.GetMinimumValue():
                        representation.SetMinimumValue(max(radius * 0.5, MIN_VALID_RADIUS))
                    if radius > representation.GetMaximumValue():
                        representation.SetMaximumValue(radius * 1.5)
                    representation.SetValue(radius)
            finally:
                self._updating_widgets = False
        self.plotter.render()


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="Reconstrucție globală a axei unui vas de rotație dintr-un ciob 3D."
    )
    parser.add_argument("--mesh", default=DEFAULT_MESH_PATH, help="Calea către fișierul OBJ.")
    parser.add_argument("--thickness", type=float, default=None, help="Grosimea reală a peretelui vasului.")
    return parser.parse_args()


def request_thickness(value):
    if value is not None:
        return float(value)
    while True:
        raw = input("Introdu grosimea reală a ciobului (în unitățile mesh-ului): ").strip()
        try:
            thickness = float(raw.replace(",", "."))
        except ValueError:
            print("Valoare invalidă. Exemplu: 2.5")
            continue
        if thickness > 0.0:
            return thickness
        print("Grosimea trebuie să fie strict pozitivă.")


def main():
    arguments = parse_arguments()
    if not os.path.exists(arguments.mesh):
        raise FileNotFoundError(f"Fișierul {arguments.mesh!r} nu a fost găsit.")
    thickness = request_thickness(arguments.thickness)
    InteractiveArheoAxis(arguments.mesh, thickness)

"""Potrivire automata si manuala a doua cioburi pe axa comuna a vasului.

Rulare:
    python pair_matching.py --mesh1 shards/model-1.obj \
        --mesh2 shards/model-2.obj --thickness 2.5

Fisierul trebuie pus langa ``axis.py`` (sau langa
``arheo_axis_global_vessel.py``). Axa este estimata separat pentru fiecare
ciob, apoi axele sunt facute coaxiale. Tasta A cauta brut pozitia ciobului 2.
"""

import argparse
import importlib
import importlib.util
import json
import os

import numpy as np
import pyvista as pv
import trimesh
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
from scipy.spatial import cKDTree


COLORS = ("#E29A52", "#42C5F5")
UI_TEXT = "#EAF2F8"
UI_MUTED = "#9FB3C8"
UI_ACCENT = "#5ED0FF"
MAX_SCORE_SAMPLES = 4500
MAX_SEARCH_SAMPLES = 1400


def load_core():
    """In varianta standalone, algoritmul axei se afla in acest fisier."""
    return sys.modules[__name__]


def load_mesh(path):
    loaded = trimesh.load(path, force="mesh", process=False)
    if isinstance(loaded, trimesh.Scene):
        if not loaded.geometry:
            raise RuntimeError(f"{path!r} nu contine geometrie.")
        loaded = trimesh.util.concatenate(tuple(loaded.geometry.values()))
    if not isinstance(loaded, trimesh.Trimesh) or len(loaded.vertices) < 3:
        raise RuntimeError(f"{path!r} nu contine un mesh triunghiular valid.")
    mesh = loaded.copy()
    mesh.remove_unreferenced_vertices()
    return mesh


def align_second_axis(mesh, source_origin, source_direction, target_origin, target_direction):
    """Aliniaza rigid axa sursa cu axa tinta, fara deplasare pe directia axei."""
    rotation = rotation_matrix_from_vectors(source_direction, target_direction)
    rotated_vertices = (np.asarray(mesh.vertices) - source_origin) @ rotation.T + source_origin
    rotated_origin = np.asarray(source_origin, dtype=float)
    delta = np.asarray(target_origin, dtype=float) - rotated_origin
    target_direction = np.asarray(target_direction, dtype=float)
    perpendicular_delta = delta - np.dot(delta, target_direction) * target_direction
    result = mesh.copy()
    result.vertices = rotated_vertices + perpendicular_delta
    return result, rotated_origin + perpendicular_delta


def rotation_matrix_from_vectors(source, target):
    source = np.asarray(source, dtype=float)
    target = np.asarray(target, dtype=float)
    source /= max(np.linalg.norm(source), 1e-12)
    target /= max(np.linalg.norm(target), 1e-12)
    cross = np.cross(source, target)
    dot = float(np.clip(np.dot(source, target), -1.0, 1.0))
    length = float(np.linalg.norm(cross))
    if length < 1e-12:
        if dot > 0.0:
            return np.eye(3)
        reference = np.array([1.0, 0.0, 0.0])
        if abs(np.dot(source, reference)) > 0.9:
            reference = np.array([0.0, 1.0, 0.0])
        axis = np.cross(source, reference)
        axis /= np.linalg.norm(axis)
        return axis_rotation_matrix(axis, 180.0)
    skew = np.array(
        [[0.0, -cross[2], cross[1]], [cross[2], 0.0, -cross[0]], [-cross[1], cross[0], 0.0]]
    )
    return np.eye(3) + skew + (skew @ skew) * ((1.0 - dot) / (length * length))


def axis_rotation_matrix(axis, angle_degrees):
    axis = np.asarray(axis, dtype=float)
    axis /= max(float(np.linalg.norm(axis)), 1e-12)
    angle = np.deg2rad(float(angle_degrees))
    skew = np.array(
        [
            [0.0, -axis[2], axis[1]],
            [axis[2], 0.0, -axis[0]],
            [-axis[1], axis[0], 0.0],
        ]
    )
    return np.eye(3) + np.sin(angle) * skew + (1.0 - np.cos(angle)) * (skew @ skew)


def apply_axis_motion(points, axis_origin, axis_direction, angle, axial_offset):
    rotation = axis_rotation_matrix(axis_direction, angle)
    moved = (np.asarray(points) - axis_origin) @ rotation.T + axis_origin
    return moved + float(axial_offset) * axis_direction, rotation


def perpendicular_basis(axis):
    axis = np.asarray(axis, dtype=float)
    axis /= max(np.linalg.norm(axis), 1e-12)
    reference = np.array([1.0, 0.0, 0.0])
    if abs(np.dot(axis, reference)) > 0.9:
        reference = np.array([0.0, 1.0, 0.0])
    u = np.cross(axis, reference)
    u /= np.linalg.norm(u)
    v = np.cross(axis, u)
    return u, v


def ring_points(center, axis, radius, count=160):
    u, v = perpendicular_basis(axis)
    angles = np.linspace(0.0, 2.0 * np.pi, count, endpoint=False)
    points = center + radius * (np.cos(angles)[:, None] * u + np.sin(angles)[:, None] * v)
    return np.vstack((points, points[0]))


def pyvista_mesh(mesh):
    faces = np.empty((len(mesh.faces), 4), dtype=np.int64)
    faces[:, 0] = 3
    faces[:, 1:] = np.asarray(mesh.faces, dtype=np.int64)
    return pv.PolyData(np.asarray(mesh.vertices, dtype=float), faces.ravel())


def deterministic_face_samples(mesh, maximum=MAX_SCORE_SAMPLES):
    """Centre de triunghiuri distribuite pe suprafata, fara randomizare."""
    centers = np.asarray(mesh.triangles_center, dtype=float)
    normals = np.asarray(mesh.face_normals, dtype=float)
    areas = np.asarray(mesh.area_faces, dtype=float)
    valid = np.isfinite(centers).all(axis=1) & np.isfinite(normals).all(axis=1)
    valid &= np.isfinite(areas) & (areas > 1e-12)
    indices = np.flatnonzero(valid)
    if len(indices) > maximum:
        # Sorteaza dupa aria fetei si ia pozitii echidistante. Fetele foarte
        # mici nu ajung sa domine scorul doar fiindca sunt numeroase.
        ordered = indices[np.argsort(areas[indices], kind="stable")]
        positions = np.linspace(0, len(ordered) - 1, maximum, dtype=int)
        indices = ordered[positions]
    return centers[indices], normals[indices]


def deterministic_boundary_samples(mesh, maximum=MAX_SCORE_SAMPLES):
    """Esantioane numai de pe contururile deschise relevante ale ciobului."""
    working = mesh.copy()
    # Pastram separarea varfurilor din OBJ. Scanarile sunt adesea inchise
    # geometric, iar muchia de fractura este codata prin separarea normalelor
    # (unirea tuturor varfurilor ar sterge tocmai acel contur).
    faces = np.asarray(working.faces, dtype=np.int64)
    vertices = np.asarray(working.vertices, dtype=float)
    face_normals = np.asarray(working.face_normals, dtype=float)
    face_ids = np.arange(len(faces), dtype=np.int64)

    directed = np.vstack(
        (faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]])
    )
    directed_face_ids = np.tile(face_ids, 3)
    undirected = np.sort(directed, axis=1)
    _, inverse, counts = np.unique(
        undirected, axis=0, return_inverse=True, return_counts=True
    )
    boundary_mask = counts[inverse] == 1
    boundary = directed[boundary_mask]
    boundary_face_ids = directed_face_ids[boundary_mask]
    if len(boundary) < 6:
        raise RuntimeError(
            "Mesh-ul nu are un contur deschis suficient pentru modul edge."
        )

    graph = coo_matrix(
        (
            np.ones(2 * len(boundary), dtype=np.uint8),
            (
                np.r_[boundary[:, 0], boundary[:, 1]],
                np.r_[boundary[:, 1], boundary[:, 0]],
            ),
        ),
        shape=(len(vertices), len(vertices)),
    ).tocsr()
    _, labels = connected_components(graph, directed=False)
    mesh_diagonal = max(float(np.linalg.norm(np.ptp(vertices, axis=0))), 1e-9)
    component_labels = labels[boundary[:, 0]]
    keep_components = []
    component_data = []
    for label in np.unique(component_labels):
        edge_ids = np.flatnonzero(component_labels == label)
        component_vertices = np.unique(boundary[edge_ids])
        span = float(np.linalg.norm(np.ptp(vertices[component_vertices], axis=0)))
        lengths = np.linalg.norm(
            vertices[boundary[edge_ids, 1]] - vertices[boundary[edge_ids, 0]], axis=1
        )
        length = float(np.sum(lengths))
        component_data.append((int(label), span, length, len(edge_ids)))

    longest = max(item[2] for item in component_data)
    for label, span, length, edge_count in component_data:
        if (
            edge_count >= 8
            and span >= 0.22 * mesh_diagonal
            and length >= 0.18 * longest
        ):
            keep_components.append(label)
    if not keep_components:
        keep_components = [max(component_data, key=lambda item: item[2])[0]]

    selected = np.isin(component_labels, keep_components)
    boundary = boundary[selected]
    boundary_face_ids = boundary_face_ids[selected]
    start = vertices[boundary[:, 0]]
    end = vertices[boundary[:, 1]]
    vectors = end - start
    lengths = np.linalg.norm(vectors, axis=1)
    valid = np.isfinite(lengths) & (lengths > 1e-10)
    start = start[valid]
    end = end[valid]
    vectors = vectors[valid]
    lengths = lengths[valid]
    normals = face_normals[boundary_face_ids[valid]]
    tangents = vectors / lengths[:, None]
    conormals = np.cross(tangents, normals)
    conormal_lengths = np.linalg.norm(conormals, axis=1)
    valid_conormals = conormal_lengths > 1e-10
    points = 0.5 * (start + end)
    points = points[valid_conormals]
    normals = normals[valid_conormals]
    tangents = tangents[valid_conormals]
    conormals = conormals[valid_conormals]
    conormals /= conormal_lengths[valid_conormals, None]
    lengths = lengths[valid_conormals]

    if len(points) > maximum:
        cumulative = np.cumsum(lengths)
        targets = np.linspace(0.0, cumulative[-1], maximum, endpoint=False)
        indices = np.searchsorted(cumulative, targets, side="left")
        indices = np.unique(np.clip(indices, 0, len(points) - 1))
        points = points[indices]
        normals = normals[indices]
        tangents = tangents[indices]
        conormals = conormals[indices]
    return points, normals, tangents, conormals


def directional_edge_contact(
    source_points,
    source_normals,
    source_tangents,
    source_conormals,
    target_points,
    target_normals,
    target_tangents,
    target_conormals,
    threshold,
):
    tree = cKDTree(target_points)
    distances, nearest = tree.query(source_points, k=1, workers=1)
    normal_dot = np.einsum("ij,ij->i", source_normals, target_normals[nearest])
    tangent_dot = np.abs(
        np.einsum("ij,ij->i", source_tangents, target_tangents[nearest])
    )
    conormal_dot = np.einsum(
        "ij,ij->i", source_conormals, target_conormals[nearest]
    )
    # Pe doua fragmente vecine, suprafetele vasului au normale asemanatoare,
    # tangentele muchiei sunt paralele, iar exteriorul celor doua contururi
    # este orientat in sensuri opuse.
    compatible = (
        (normal_dot > 0.10)
        & (tangent_dot > 0.45)
        & (conormal_dot < -0.05)
    )
    contact = compatible & (distances <= threshold)
    candidates = np.flatnonzero(compatible)
    if len(candidates):
        order = candidates[np.argsort(distances[candidates])]
        robust_count = min(len(order), max(10, len(source_points) // 18))
        closest = order[:robust_count]
        median_distance = float(np.median(distances[closest]))
        orientation = float(
            np.median(
                0.40 * normal_dot[closest]
                + 0.35 * tangent_dot[closest]
                + 0.25 * (-conormal_dot[closest])
            )
        )
    else:
        closest = np.empty(0, dtype=int)
        median_distance = float("inf")
        orientation = 0.0
    return {
        "median": median_distance,
        "orientation": orientation,
        "contacts": int(np.count_nonzero(contact)),
        "contact_fraction": float(np.count_nonzero(contact)) / max(len(source_points), 1),
        "source_contact_indices": np.flatnonzero(contact),
        "nearest": nearest,
        "distances": distances,
    }


def surface_overlap_fraction(points1, normals1, points2, normals2, tolerance):
    if not len(points1) or not len(points2):
        return 0.0
    tree2 = cKDTree(points2)
    distances12, nearest12 = tree2.query(points1, k=1, workers=1)
    dots12 = np.einsum("ij,ij->i", normals1, normals2[nearest12])
    tree1 = cKDTree(points1)
    distances21, nearest21 = tree1.query(points2, k=1, workers=1)
    dots21 = np.einsum("ij,ij->i", normals2, normals1[nearest21])
    overlap12 = np.mean((distances12 < tolerance) & (dots12 > 0.35))
    overlap21 = np.mean((distances21 < tolerance) & (dots21 > 0.35))
    return float(0.5 * (overlap12 + overlap21))


def matching_score(
    points1,
    normals1,
    tangents1,
    conormals1,
    points2,
    normals2,
    tangents2,
    conormals2,
    threshold,
    surface_geometry=None,
):
    forward = directional_edge_contact(
        points1, normals1, tangents1, conormals1,
        points2, normals2, tangents2, conormals2, threshold,
    )
    backward = directional_edge_contact(
        points2, normals2, tangents2, conormals2,
        points1, normals1, tangents1, conormals1, threshold,
    )
    finite_medians = [x["median"] for x in (forward, backward) if np.isfinite(x["median"])]
    median_distance = float(np.mean(finite_medians)) if finite_medians else threshold * 5.0
    contact_fraction = float(
        np.sqrt(forward["contact_fraction"] * backward["contact_fraction"])
    )
    orientation = 0.5 * (forward["orientation"] + backward["orientation"])

    distance_part = np.exp(-median_distance / max(threshold * 0.55, 1e-9))
    contact_part = 1.0 - np.exp(-contact_fraction / 0.055)
    orientation_part = np.clip((orientation - 0.20) / 0.80, 0.0, 1.0)
    overlap = 0.0
    if surface_geometry is not None:
        surface_points1, surface_normals1, surface_points2, surface_normals2, tolerance = (
            surface_geometry
        )
        overlap = surface_overlap_fraction(
            surface_points1,
            surface_normals1,
            surface_points2,
            surface_normals2,
            tolerance,
        )
    collision_penalty = np.exp(-max(0.0, overlap - 0.012) / 0.035)
    score = (
        100.0
        * distance_part
        * (0.12 + 0.58 * contact_part + 0.30 * orientation_part)
        * collision_penalty
    )
    forward["overlap_fraction"] = overlap
    backward["overlap_fraction"] = overlap
    return float(np.clip(score, 0.0, 100.0)), median_distance, forward, backward


def profile_compatibility_score(z1, radii1, z2, radii2, thickness):
    z1 = np.asarray(z1, dtype=float)
    z2 = np.asarray(z2, dtype=float)
    radii1 = np.asarray(radii1, dtype=float)
    radii2 = np.asarray(radii2, dtype=float)
    z = np.concatenate((z1, z2))
    radii = np.concatenate((radii1, radii2))
    scale_z = max(float(np.std(z)), 1e-8)
    normalized_z = (z - np.mean(z)) / scale_z
    coefficients = np.polyfit(normalized_z, radii, 2)
    residuals = radii - np.polyval(coefficients, normalized_z)
    rms = float(np.sqrt(np.mean(np.square(residuals))))
    slope1 = float(np.polyfit(z1, radii1, 1)[0])
    slope2 = float(np.polyfit(z2, radii2, 1)[0])
    slope_difference = abs(slope1 - slope2)
    interval_gap = max(
        0.0,
        float(max(np.min(z1), np.min(z2)) - min(np.max(z1), np.max(z2))),
    )
    supported_span = max(
        float(np.ptp(z1)),
        float(np.ptp(z2)),
        4.0 * float(thickness),
        1e-8,
    )
    # Un polinom de gradul doi poate obtine artificial un reziduu aproape
    # nul daca cele doua fragmente sunt impinse foarte departe. Acceptam un
    # interval nescanat comparabil cu un fragment, apoi penalizam extrapolarea.
    unsupported_gap = max(0.0, interval_gap - supported_span)
    gap_penalty = (unsupported_gap / (2.0 * supported_span)) ** 2
    score = 100.0 / (
        1.0
        + (rms / max(1.5 * thickness, 1e-8)) ** 2
        + (slope_difference / 0.60) ** 2
        + gap_penalty
    )
    return float(np.clip(score, 0.0, 100.0)), rms, slope_difference


class PairMatchingApp:
    def __init__(
        self,
        mesh1_path,
        mesh2_path,
        thickness,
        output_dir,
        target_score,
        max_iterations,
        auto_start=True,
        match_mode="auto",
    ):
        self.thickness = float(thickness)
        self.mesh_paths = [os.path.abspath(mesh1_path), os.path.abspath(mesh2_path)]
        self.output_dir = os.path.abspath(output_dir)
        self.target_score = float(target_score)
        self.max_iterations = int(max_iterations)
        self.auto_start = bool(auto_start)
        self.match_mode = str(match_mode).lower()
        self.simple_edge_interface = self.match_mode in {"profile", "edge"}
        self.selected_match_mode = "profile" if self.match_mode == "auto" else self.match_mode
        self.auto_scores = {"profile": None, "edge": None}
        if not np.isfinite(self.thickness) or self.thickness <= 0.0:
            raise ValueError("Grosimea trebuie sa fie strict pozitiva.")
        if not np.isfinite(self.target_score) or not 0.0 < self.target_score <= 100.0:
            raise ValueError("Scorul tinta trebuie sa fie in intervalul (0, 100].")
        if self.max_iterations < 1:
            raise ValueError("Numarul maxim de iteratii trebuie sa fie cel putin 1.")
        if self.match_mode not in {"auto", "profile", "edge"}:
            raise ValueError(
                "Modul de potrivire trebuie sa fie 'auto', 'profile' sau 'edge'."
            )

        core = load_core()
        raw1 = load_mesh(mesh1_path)
        raw2 = load_mesh(mesh2_path)
        print("Estimez separat axa primului ciob...")
        aligned1, sections1, axis_origin1, axis_direction1, _ = (
            core.build_automatic_solution(raw1.copy(), self.thickness)
        )
        print("Estimez separat axa celui de-al doilea ciob...")
        aligned2, sections2, axis_origin2, axis_direction2, _ = (
            core.build_automatic_solution(raw2.copy(), self.thickness)
        )

        # Dupa estimarea independenta, eliminam doar diferenta laterala dintre
        # axe. Pozitia pe axa si rotatia raman necunoscutele cautarii brute.
        aligned2, _ = align_second_axis(
            aligned2,
            np.asarray(axis_origin2, dtype=float),
            np.asarray(axis_direction2, dtype=float),
            np.asarray(axis_origin1, dtype=float),
            np.asarray(axis_direction1, dtype=float),
        )
        self.base_meshes = [aligned1, aligned2]
        self.axis_origin = np.asarray(axis_origin1, dtype=float)
        self.axis_direction = np.asarray(axis_direction1, dtype=float)
        self.axis_direction /= np.linalg.norm(self.axis_direction)
        self.flip_axis, _ = perpendicular_basis(self.axis_direction)
        self.mesh_size = max(
            float(np.linalg.norm(m.bounds[1] - m.bounds[0])) for m in self.base_meshes
        )
        self.axis_length = max(self.mesh_size * 2.4, 1.0)
        self.contact_threshold = max(self.thickness * 1.6, self.mesh_size * 0.035)
        self.angles = [0.0, 0.0]
        self.offsets = [0.0, 0.0]
        self.axis_flips = [False, False]
        self._ready = False
        self._updating_controls = False
        self.search_iterations = 0
        self.search_completed = False
        self._search_running = False

        self.base_vertices = [np.asarray(m.vertices, dtype=float).copy() for m in self.base_meshes]
        samples = [deterministic_face_samples(m) for m in self.base_meshes]
        self.base_sample_points = [item[0] for item in samples]
        self.base_sample_normals = [item[1] for item in samples]
        search_samples = [deterministic_face_samples(m, MAX_SEARCH_SAMPLES) for m in self.base_meshes]
        self.search_points = [item[0] for item in search_samples]
        self.search_normals = [item[1] for item in search_samples]
        if self.match_mode in {"auto", "edge"}:
            edge_samples = [
                deterministic_boundary_samples(m, MAX_SCORE_SAMPLES)
                for m in self.base_meshes
            ]
            search_edge_samples = [
                deterministic_boundary_samples(m, MAX_SEARCH_SAMPLES)
                for m in self.base_meshes
            ]
            self.base_edge_points = [item[0] for item in edge_samples]
            self.base_edge_normals = [item[1] for item in edge_samples]
            self.base_edge_tangents = [item[2] for item in edge_samples]
            self.base_edge_conormals = [item[3] for item in edge_samples]
            self.search_edge_points = [item[0] for item in search_edge_samples]
            self.search_edge_normals = [item[1] for item in search_edge_samples]
            self.search_edge_tangents = [item[2] for item in search_edge_samples]
            self.search_edge_conormals = [item[3] for item in search_edge_samples]
        self.profile_axial = [
            np.asarray(
                [np.dot(section["center"] - self.axis_origin, self.axis_direction) for section in sections],
                dtype=float,
            )
            for sections in (sections1, sections2)
        ]
        self.profile_radii = [
            np.asarray([section["inner_radius"] for section in sections], dtype=float)
            for sections in (sections1, sections2)
        ]

        self.gizmo_axial_positions = []
        self.gizmo_radii = []
        self.gizmo_reference_vectors = []
        u, _ = perpendicular_basis(self.axis_direction)
        for mesh in self.base_meshes:
            vertices = np.asarray(mesh.vertices, dtype=float)
            relative = vertices - self.axis_origin
            axial_position = float(np.median(relative @ self.axis_direction))
            radial = relative - np.outer(relative @ self.axis_direction, self.axis_direction)
            radial_lengths = np.linalg.norm(radial, axis=1)
            radius = max(float(np.percentile(radial_lengths, 95)) * 1.12, self.mesh_size * 0.08)
            centroid_radial = radial.mean(axis=0)
            if np.linalg.norm(centroid_radial) < 1e-8:
                centroid_radial = u
            centroid_radial /= np.linalg.norm(centroid_radial)
            self.gizmo_axial_positions.append(axial_position)
            self.gizmo_radii.append(radius)
            self.gizmo_reference_vectors.append(centroid_radial)

        self.plotter = pv.Plotter(
            title="ArheoMatch — potrivire pe axa vasului",
            window_size=(1500, 900),
        )
        self.plotter.set_background("#0B1118", top="#182532")
        try:
            self.plotter.enable_anti_aliasing("fxaa")
        except (AttributeError, RuntimeError):
            pass
        self.display_meshes = [pyvista_mesh(m) for m in self.base_meshes]
        for index, display in enumerate(self.display_meshes):
            self.plotter.add_mesh(
                display,
                color=COLORS[index],
                opacity=0.92,
                smooth_shading=True,
                ambient=0.24,
                diffuse=0.76,
                specular=0.28,
                specular_power=24,
                name=f"shard_{index + 1}",
            )

        self.axis_mesh = pv.Line(
            self.axis_origin - self.axis_direction * self.axis_length / 2.0,
            self.axis_origin + self.axis_direction * self.axis_length / 2.0,
        )
        self.plotter.add_mesh(
            self.axis_mesh,
            color="#F4F7FA",
            line_width=3,
            opacity=0.75,
            name="common_axis",
        )
        # PyVista nu permite adaugarea initiala a unui PolyData complet gol.
        # Punctul provizoriu este inlocuit la primul update, inainte de show().
        self.contact_cloud = pv.PolyData(np.asarray([self.axis_origin], dtype=float))
        self.plotter.add_mesh(
            self.contact_cloud,
            color="yellow",
            point_size=7,
            render_points_as_spheres=True,
            name="contacts",
        )

        self.gizmo_rings = []
        self.center_widgets = []
        self.rotation_widgets = []
        for index in range(2):
            center, handle = self.gizmo_positions(index)
            ring = pv.Spline(ring_points(center, self.axis_direction, self.gizmo_radii[index]))
            self.gizmo_rings.append(ring)
            if not self.simple_edge_interface or index == 1:
                self.plotter.add_mesh(
                    ring,
                    color=COLORS[index],
                    line_width=2,
                    opacity=0.38,
                    name=f"gizmo_ring_{index}",
                )
            if self.simple_edge_interface and index == 0:
                self.center_widgets.append(None)
                self.rotation_widgets.append(None)
                continue
            center_widget = self.plotter.add_sphere_widget(
                self.make_center_handle_callback(index),
                center=center,
                radius=self.mesh_size * 0.014,
                color=COLORS[index],
                test_callback=False,
            )
            rotation_widget = self.plotter.add_sphere_widget(
                self.make_rotation_handle_callback(index),
                center=handle,
                radius=self.mesh_size * 0.012,
                color="#FFD166",
                test_callback=False,
            )
            self.center_widgets.append(center_widget)
            self.rotation_widgets.append(rotation_widget)

        if self.simple_edge_interface:
            slider_specs = (
                (1, "Rotatie ciob mobil", "angle", -180.0, 180.0, 0.65),
                (
                    1,
                    "Pozitie pe axa",
                    "offset",
                    -self.mesh_size,
                    self.mesh_size,
                    0.50,
                ),
            )
        else:
            slider_specs = (
                (0, "Ciob 1  |  rotatie", "angle", -180.0, 180.0, 0.76),
                (0, "Ciob 1  |  pozitie pe axa", "offset", -self.mesh_size, self.mesh_size, 0.63),
                (1, "Ciob 2  |  rotatie", "angle", -180.0, 180.0, 0.45),
                (1, "Ciob 2  |  pozitie pe axa", "offset", -self.mesh_size, self.mesh_size, 0.32),
            )
        self.sliders = []
        self.slider_widgets = {}
        previous_suppress_rendering = self.plotter.suppress_rendering
        self.plotter.suppress_rendering = True
        for shard, title, kind, lower, upper, y in slider_specs:
            callback = self.make_slider_callback(shard, kind)
            widget = self.plotter.add_slider_widget(
                callback,
                rng=(lower, upper),
                value=0.0,
                title=None,
                pointa=(0.025, y),
                pointb=(0.265, y),
                color=COLORS[shard],
                style="modern",
                title_height=0.018,
                title_color=UI_TEXT,
                fmt="{:0.1f}",
                slider_width=0.018,
                tube_width=0.006,
            )
            representation = widget.GetRepresentation()
            representation.GetLabelProperty().SetShadow(False)
            representation.GetTitleProperty().SetShadow(False)
            representation.SetTitleText(title)
            self.sliders.append(widget)
            self.slider_widgets[(shard, kind)] = widget
        self.plotter.suppress_rendering = previous_suppress_rendering

        if self.simple_edge_interface and self.match_mode == "profile":
            header_text = (
                "POTRIVIRE CIOBURI\n"
                "Ciob 1 fix • ciob 2 mobil\n"
                "Compatibilitate geometrica a vasului"
            )
        elif self.simple_edge_interface:
            header_text = (
                "TEST CONTACT CIOBURI\n"
                "Ciob 1 fix • ciob 2 mobil\n"
                "Verificare muchie de ruptura"
            )
        else:
            header_text = (
                "ARHEOMATCH\n"
                f"Potrivire: {self.match_mode.upper()}\n"
                "Doua fragmente • o axa comuna"
            )
        self.plotter.add_text(
            header_text,
            position=(0.025, 0.89),
            viewport=True,
            font_size=10,
            color=UI_TEXT,
            font="arial",
            shadow=False,
            name="app_header",
        )
        help_text = (
            "A analiza   R reset   S salvare   F reincadrare   2 inverseaza ciobul mobil"
            if self.simple_edge_interface
            else "A cautare   R reset   S salvare   F reincadrare   1/2 inverseaza ciobul"
        )
        self.plotter.add_text(
            help_text,
            position=(0.025, 0.055),
            viewport=True,
            font_size=8,
            color=UI_MUTED,
            font="arial",
            name="help",
        )
        self.plotter.add_key_event("a", self.automatic_match)
        self.plotter.add_key_event("r", self.reset)
        self.plotter.add_key_event("s", self.save_result)
        self.plotter.add_key_event("f", self.frame_geometry)
        if not self.simple_edge_interface:
            self.plotter.add_key_event("1", lambda: self.toggle_shard_orientation(0))
        self.plotter.add_key_event("2", lambda: self.toggle_shard_orientation(1))
        self._ready = True
        self.update_scene()
        self.plotter.add_axes(
            line_width=2,
            color=UI_MUTED,
            viewport=(0.88, 0.035, 0.975, 0.155),
        )
        self.frame_geometry(render=False)
        if self.auto_start:
            self.plotter.add_text(
                "ANALIZA IN CURS\nPornesc potrivirea automata...",
                position=(0.34, 0.89),
                viewport=True,
                font_size=8,
                color=UI_ACCENT,
                font="arial",
                shadow=False,
                name="search_status",
            )
            self.plotter.render()
            self.automatic_match()
        self.plotter.show()

    def frame_geometry(self, render=True):
        points = np.vstack([np.asarray(mesh.points) for mesh in self.display_meshes])
        if not len(points):
            return
        minimum = points.min(axis=0)
        maximum = points.max(axis=0)
        extent = np.maximum(maximum - minimum, self.mesh_size * 0.05)
        padding = extent * 0.16
        lower = minimum - padding
        upper = maximum + padding
        bounds = (
            lower[0], upper[0], lower[1], upper[1], lower[2], upper[2]
        )
        self.plotter.reset_camera(bounds=bounds, render=False)
        if render:
            self.plotter.render()

    def effective_match_mode(self):
        return self.selected_match_mode if self.match_mode == "auto" else self.match_mode

    def edge_verdict(self, score, contacts=None):
        enough_contacts = contacts is None or contacts >= 8
        if score >= self.target_score and enough_contacts:
            return "DA — MUCHII COMPATIBILE", "#7EE787"
        possible_threshold = max(40.0, self.target_score * 0.65)
        possible_contacts = contacts is None or contacts >= 4
        if score >= possible_threshold and possible_contacts:
            return "POSIBIL — VERIFICARE MANUALA", "#FFD166"
        return "NU — FARA CONTACT COMPATIBIL", "#FF7B72"

    def toggle_shard_orientation(self, index):
        """Inverseaza rigid un ciob in jurul centrului sau axial curent."""
        if not self._ready or index not in (0, 1):
            return
        old_sign = -1.0 if self.axis_flips[index] else 1.0
        axial_center = (
            old_sign * self.gizmo_axial_positions[index] + self.offsets[index]
        )
        self.axis_flips[index] = not self.axis_flips[index]
        new_sign = -1.0 if self.axis_flips[index] else 1.0
        self.offsets[index] = (
            axial_center - new_sign * self.gizmo_axial_positions[index]
        )
        self.search_completed = False
        self.plotter.add_text(
            f"ORIENTARE CORECTATA MANUAL\n"
            f"Ciobul {index + 1}: "
            f"{'inversat' if self.axis_flips[index] else 'normal'}",
            position=(0.34, 0.89),
            viewport=True,
            font_size=8,
            color="#FFD166",
            font="arial",
            shadow=False,
            name="search_status",
        )
        self.update_scene(sync_controls=True)
        self.frame_geometry()

    def transform_points(self, index, points, angle=None, offset=None, flipped=None):
        angle = self.angles[index] if angle is None else float(angle)
        offset = self.offsets[index] if offset is None else float(offset)
        flipped = self.axis_flips[index] if flipped is None else bool(flipped)
        transformed = np.asarray(points, dtype=float)
        total_rotation = np.eye(3)
        if flipped:
            flip_rotation = axis_rotation_matrix(self.flip_axis, 180.0)
            transformed = (transformed - self.axis_origin) @ flip_rotation.T + self.axis_origin
            total_rotation = flip_rotation
        axis_rotation = axis_rotation_matrix(self.axis_direction, angle)
        transformed = (transformed - self.axis_origin) @ axis_rotation.T + self.axis_origin
        transformed += offset * self.axis_direction
        total_rotation = axis_rotation @ total_rotation
        return transformed, total_rotation

    def gizmo_positions(self, index):
        sign = -1.0 if self.axis_flips[index] else 1.0
        axial = sign * self.gizmo_axial_positions[index] + self.offsets[index]
        center = self.axis_origin + axial * self.axis_direction
        rotation = axis_rotation_matrix(self.axis_direction, self.angles[index])
        if self.axis_flips[index]:
            rotation = rotation @ axis_rotation_matrix(self.flip_axis, 180.0)
        radial = rotation @ self.gizmo_reference_vectors[index]
        handle = center + self.gizmo_radii[index] * radial
        return center, handle

    @staticmethod
    def widget_actor(widget):
        if isinstance(widget, (list, tuple)):
            return widget[0]
        return widget

    def make_center_handle_callback(self, index):
        def callback(new_position):
            if not self._ready or self._updating_controls:
                return
            axial = float(np.dot(np.asarray(new_position) - self.axis_origin, self.axis_direction))
            sign = -1.0 if self.axis_flips[index] else 1.0
            self.offsets[index] = axial - sign * self.gizmo_axial_positions[index]
            self.update_scene(sync_controls=True)

        return callback

    def make_rotation_handle_callback(self, index):
        def callback(new_position):
            if not self._ready or self._updating_controls:
                return
            center, _ = self.gizmo_positions(index)
            radial = np.asarray(new_position, dtype=float) - center
            radial -= np.dot(radial, self.axis_direction) * self.axis_direction
            length = float(np.linalg.norm(radial))
            if length < 1e-8:
                return
            radial /= length
            reference = self.gizmo_reference_vectors[index]
            if self.axis_flips[index]:
                reference = axis_rotation_matrix(self.flip_axis, 180.0) @ reference
            sine = float(np.dot(self.axis_direction, np.cross(reference, radial)))
            cosine = float(np.clip(np.dot(reference, radial), -1.0, 1.0))
            self.angles[index] = float(np.rad2deg(np.arctan2(sine, cosine)))
            self.update_scene(sync_controls=True)

        return callback

    def current_geometry(self, index):
        points, rotation = self.transform_points(index, self.base_sample_points[index])
        normals = self.base_sample_normals[index] @ rotation.T
        return points, normals

    def current_edge_geometry(
        self, index, search=False, angle=None, offset=None, flipped=None
    ):
        if search:
            points = self.search_edge_points[index]
            normals = self.search_edge_normals[index]
            tangents = self.search_edge_tangents[index]
            conormals = self.search_edge_conormals[index]
        else:
            points = self.base_edge_points[index]
            normals = self.base_edge_normals[index]
            tangents = self.base_edge_tangents[index]
            conormals = self.base_edge_conormals[index]
        moved_points, rotation = self.transform_points(
            index, points, angle=angle, offset=offset, flipped=flipped
        )
        return (
            moved_points,
            normals @ rotation.T,
            tangents @ rotation.T,
            conormals @ rotation.T,
        )

    def current_profile_metrics(self):
        profile_z = []
        for index in range(2):
            sign = -1.0 if self.axis_flips[index] else 1.0
            profile_z.append(sign * self.profile_axial[index] + self.offsets[index])
        return profile_compatibility_score(
            profile_z[0],
            self.profile_radii[0],
            profile_z[1],
            self.profile_radii[1],
            self.thickness,
        )

    def make_slider_callback(self, shard, kind):
        def callback(value):
            if not self._ready or self._updating_controls:
                return
            if kind == "angle":
                self.angles[shard] = float(value)
            else:
                self.offsets[shard] = float(value)
            self.update_scene(sync_controls=True)

        return callback

    def update_scene(self, sync_controls=False):
        sample_points = []
        sample_normals = []
        for index in range(2):
            moved_vertices, _ = self.transform_points(index, self.base_vertices[index])
            self.display_meshes[index].points = moved_vertices
            self.display_meshes[index].Modified()
            points, normals = self.current_geometry(index)
            sample_points.append(points)
            sample_normals.append(normals)

        state_label = "dupa cautare" if self.search_completed else "pozitie initiala neoptimizata"
        active_mode = self.effective_match_mode()
        if self.match_mode == "auto" and self.search_completed:
            state_label = f"AUTO a ales {active_mode.upper()}"
        if active_mode == "profile":
            score, profile_rms, slope_difference = self.current_profile_metrics()
            self.contact_cloud.points = np.empty((0, 3))
            self.contact_cloud.Modified()
            profile_text = (
                f"SCOR POTRIVIRE   {score:5.1f}/100\n"
                f"Eroare profil   {profile_rms:.3f}\n"
                f"Diferenta panta   {slope_difference:.3f}\n"
                f"Grosime {self.thickness:g}"
                if self.simple_edge_interface
                else f"SCOR PROFIL   {score:5.1f}/100\n"
                f"{state_label}\n"
                f"Eroare {profile_rms:.3f}   |   Δ panta {slope_difference:.3f}\n"
                f"Grosime comuna {self.thickness:g}"
            )
            self.plotter.add_text(
                profile_text,
                position=(0.735, 0.87),
                viewport=True,
                font_size=9,
                color=UI_TEXT,
                font="arial",
                shadow=False,
                name="score_info",
            )
        else:
            edge1 = self.current_edge_geometry(0)
            edge2 = self.current_edge_geometry(1)
            score, median_distance, forward, backward = matching_score(
                *edge1,
                *edge2,
                self.contact_threshold,
                surface_geometry=(
                    sample_points[0],
                    sample_normals[0],
                    sample_points[1],
                    sample_normals[1],
                    max(self.thickness * 0.35, self.mesh_size * 0.006),
                ),
            )
            contact_indices = forward["source_contact_indices"]
            if len(contact_indices) > 500:
                contact_indices = contact_indices[
                    np.linspace(0, len(contact_indices) - 1, 500, dtype=int)
                ]
            self.contact_cloud.points = edge1[0][contact_indices]
            self.contact_cloud.Modified()
            contacts = forward["contacts"] + backward["contacts"]
            distance_label = f"{median_distance:.3f}" if np.isfinite(median_distance) else "n/a"
            verdict, verdict_color = self.edge_verdict(score, contacts)
            score_text = (
                f"{verdict}\n"
                f"Scor contact   {score:5.1f}/100\n"
                f"Distanta robusta   {distance_label}\n"
                f"Puncte compatibile   {contacts}\n"
                f"Grosime {self.thickness:g}"
                if self.simple_edge_interface
                else f"SCOR MUCHIE   {score:5.1f}/100\n"
                f"{state_label}\n"
                f"Distanta {distance_label}   |   contacte {contacts}\n"
                f"Grosime comuna {self.thickness:g}"
            )
            self.plotter.add_text(
                score_text,
                position=(0.735, 0.87),
                viewport=True,
                font_size=9,
                color=verdict_color if self.simple_edge_interface else UI_TEXT,
                font="arial",
                shadow=False,
                name="score_info",
            )
        if sync_controls:
            self.sync_controls()
        self.plotter.render()

    def sync_controls(self):
        self._updating_controls = True
        try:
            for index in range(2):
                center, handle = self.gizmo_positions(index)
                new_ring = pv.Spline(
                    ring_points(center, self.axis_direction, self.gizmo_radii[index])
                )
                self.gizmo_rings[index].copy_from(new_ring)
                if self.center_widgets[index] is not None:
                    self.widget_actor(self.center_widgets[index]).SetCenter(center)
                if self.rotation_widgets[index] is not None:
                    self.widget_actor(self.rotation_widgets[index]).SetCenter(handle)
                angle_widget = self.slider_widgets.get((index, "angle"))
                offset_widget = self.slider_widgets.get((index, "offset"))
                if angle_widget is None or offset_widget is None:
                    continue
                angle_slider = angle_widget.GetRepresentation()
                offset_slider = offset_widget.GetRepresentation()
                if self.offsets[index] < offset_slider.GetMinimumValue():
                    offset_slider.SetMinimumValue(self.offsets[index] - self.mesh_size * 0.1)
                if self.offsets[index] > offset_slider.GetMaximumValue():
                    offset_slider.SetMaximumValue(self.offsets[index] + self.mesh_size * 0.1)
                angle_slider.SetValue(self.angles[index])
                offset_slider.SetValue(self.offsets[index])
        finally:
            self._updating_controls = False

    def evaluate_search_candidate(
        self, angle, offset, fixed_edge, fixed_surface, flipped=None
    ):
        edge2 = self.current_edge_geometry(
            1, search=True, angle=angle, offset=offset, flipped=flipped
        )
        surface_points2, rotation = self.transform_points(
            1,
            self.search_points[1],
            angle=angle,
            offset=offset,
            flipped=flipped,
        )
        surface_normals2 = self.search_normals[1] @ rotation.T
        return matching_score(
            *fixed_edge,
            *edge2,
            self.contact_threshold,
            surface_geometry=(
                fixed_surface[0],
                fixed_surface[1],
                surface_points2,
                surface_normals2,
                max(self.thickness * 0.35, self.mesh_size * 0.006),
            ),
        )[0]

    def search_grid(
        self,
        angles,
        offsets,
        fixed_edge,
        fixed_surface,
        best,
        flipped=None,
        iteration_limit=None,
    ):
        best_score, best_angle, best_offset = best
        limit = self.max_iterations if iteration_limit is None else int(iteration_limit)
        for angle in angles:
            for offset in offsets:
                if self.search_iterations >= min(self.max_iterations, limit):
                    return best_score, best_angle, best_offset, True
                score = self.evaluate_search_candidate(
                    float(angle),
                    float(offset),
                    fixed_edge,
                    fixed_surface,
                    flipped=flipped,
                )
                self.search_iterations += 1
                if score > best_score:
                    best_score = score
                    best_angle = float(angle)
                    best_offset = float(offset)
                if self.search_iterations % 40 == 0:
                    self.plotter.add_text(
                        f"CAUTARE MUCHIE   {self.search_iterations}/{self.max_iterations}\n"
                        f"Cel mai bun scor   {best_score:.2f}/100",
                        position=(0.34, 0.89),
                        viewport=True,
                        font_size=8,
                        color=UI_ACCENT,
                        font="arial",
                        shadow=False,
                        name="search_status",
                    )
                    self.plotter.render()
                    try:
                        self.plotter.iren.process_events()
                    except (AttributeError, RuntimeError):
                        pass
                if best_score >= self.target_score:
                    return best_score, best_angle, best_offset, True
        return best_score, best_angle, best_offset, False

    def automatic_profile_match(self):
        if not self._ready or self.max_iterations <= 0 or self._search_running:
            return
        self._search_running = True
        self.search_iterations = 0
        self.search_completed = False
        sign1 = -1.0 if self.axis_flips[0] else 1.0
        z1 = sign1 * self.profile_axial[0] + self.offsets[0]
        best_score = -np.inf
        best_offset = self.offsets[1]
        best_flip = self.axis_flips[1]
        budget_per_flip = max(30, min(700, self.max_iterations // 2))

        stopped = False
        for flipped in (False, True):
            sign2 = -1.0 if flipped else 1.0
            base_z2 = sign2 * self.profile_axial[1]
            lower = float(np.min(z1) - np.max(base_z2) - self.mesh_size)
            upper = float(np.max(z1) - np.min(base_z2) + self.mesh_size)
            for offset in np.linspace(lower, upper, budget_per_flip):
                if self.search_iterations >= self.max_iterations:
                    stopped = True
                    break
                score, _, _ = profile_compatibility_score(
                    z1,
                    self.profile_radii[0],
                    base_z2 + offset,
                    self.profile_radii[1],
                    self.thickness,
                )
                self.search_iterations += 1
                if score > best_score:
                    best_score = score
                    best_offset = float(offset)
                    best_flip = flipped
                if self.search_iterations % 50 == 0:
                    self.plotter.add_text(
                        f"CAUTARE PROFIL   {self.search_iterations}/{self.max_iterations}\n"
                        f"Cel mai bun scor   {best_score:.2f}/100",
                        position=(0.34, 0.89),
                        viewport=True,
                        font_size=8,
                        color=UI_ACCENT,
                        font="arial",
                        shadow=False,
                        name="search_status",
                    )
                    self.plotter.render()
            if stopped:
                break

        self.axis_flips[1] = best_flip
        self.offsets[1] = best_offset

        # Rotatia in jurul axei nu modifica profilul unui vas de rotatie.
        # Alegem doar un unghi de afisare care evita suprapunerea cioburilor.
        fixed_points, _ = self.transform_points(0, self.search_points[0])
        fixed_tree = cKDTree(fixed_points)
        safe_candidates = []
        fallback_candidates = []
        for angle in np.arange(-180.0, 180.0, 10.0):
            points2, _ = self.transform_points(
                1,
                self.search_points[1],
                angle=angle,
                offset=best_offset,
                flipped=best_flip,
            )
            distances, _ = fixed_tree.query(points2, k=1, workers=1)
            clearance = float(np.quantile(distances, 0.01))
            proximity = float(np.quantile(distances, 0.05))
            fallback_candidates.append((clearance, float(angle)))
            if clearance >= self.thickness * 0.75:
                safe_candidates.append((proximity, float(angle)))
        if safe_candidates:
            _, best_angle = min(safe_candidates, key=lambda item: item[0])
        else:
            _, best_angle = max(fallback_candidates, key=lambda item: item[0])
        self.angles[1] = best_angle
        self.search_completed = True
        reason = (
            f"tinta {self.target_score:g} atinsa"
            if best_score >= self.target_score
            else "toate pozitiile de profil au fost testate"
        )
        if self.simple_edge_interface:
            status_text = (
                "ANALIZA TERMINATA\n"
                f"{self.search_iterations} pozitii testate"
            )
            status_color = UI_ACCENT
        else:
            status_text = (
                f"POTRIVIRE FINALIZATA   •   {reason}\n"
                f"{self.search_iterations} evaluari   |   scor {best_score:.2f}/100\n"
                f"Orientare inversata: {'da' if best_flip else 'nu'}"
            )
            status_color = "#7EE787"
        self.plotter.add_text(
            status_text,
            position=(0.34, 0.89),
            viewport=True,
            font_size=8,
            color=status_color,
            font="arial",
            shadow=False,
            name="search_status",
        )
        print(
            f"Potrivire profil: {self.search_iterations} evaluari, scor={best_score:.3f}, "
            f"flip={best_flip}, rotatie afisare={best_angle:.3f}, deplasare={best_offset:.3f}"
        )
        self.update_scene(sync_controls=True)
        self.frame_geometry()
        self._search_running = False

    def automatic_match(self):
        """Selecteaza strategia ceruta si porneste cautarea automata."""
        if self.match_mode == "auto":
            self.automatic_auto_match()
            return
        if self.match_mode == "profile":
            self.automatic_profile_match()
            return
        self.automatic_edge_match()

    def automatic_edge_match(self):
        """Cauta muchia comuna in ambele orientari posibile ale axei."""
        if not self._ready or self.max_iterations <= 0 or self._search_running:
            return
        self._search_running = True
        self.search_iterations = 0
        self.search_completed = False
        fixed_points, fixed_rotation = self.transform_points(0, self.search_points[0])
        fixed_normals = self.search_normals[0] @ fixed_rotation.T
        fixed_surface = (fixed_points, fixed_normals)
        fixed_edge = self.current_edge_geometry(0, search=True)
        axial1 = (fixed_points - self.axis_origin) @ self.axis_direction
        margin = self.contact_threshold * 2.0
        global_best = (-np.inf, 0.0, 0.0, False)
        first_budget = max(1, self.max_iterations // 2)
        orientation_limits = (first_budget, self.max_iterations)

        for flipped, iteration_limit in zip((False, True), orientation_limits):
            if self.search_iterations >= self.max_iterations:
                break
            self.axis_flips[1] = flipped
            base_points2, _ = self.transform_points(
                1,
                self.search_points[1],
                angle=0.0,
                offset=0.0,
                flipped=flipped,
            )
            axial2 = (base_points2 - self.axis_origin) @ self.axis_direction
            lower_offset = float(np.min(axial1) - np.max(axial2) - margin)
            upper_offset = float(np.max(axial1) - np.min(axial2) + margin)
            budget = max(1, iteration_limit - self.search_iterations)
            angle_count = 30 if budget >= 900 else max(6, min(20, budget // 18))
            reserve = min(98, max(0, budget // 4))
            offset_count = max(
                3, min(29, max(3, (budget - reserve) // max(angle_count, 1)))
            )
            coarse_angles = np.linspace(-180.0, 180.0, angle_count, endpoint=False)
            coarse_offsets = np.linspace(lower_offset, upper_offset, offset_count)
            current_score = self.evaluate_search_candidate(
                0.0, 0.0, fixed_edge, fixed_surface, flipped=flipped
            )
            local_best = (current_score, 0.0, 0.0)
            best_score, best_angle, best_offset, stopped = self.search_grid(
                coarse_angles,
                coarse_offsets,
                fixed_edge,
                fixed_surface,
                local_best,
                flipped=flipped,
                iteration_limit=iteration_limit,
            )

            angle_step = 360.0 / max(angle_count, 1)
            offset_step = max(
                (upper_offset - lower_offset) / max(offset_count - 1, 1),
                self.thickness * 0.25,
            )
            for _ in range(2):
                if stopped:
                    break
                new_angle_step = angle_step / 4.0
                new_offset_step = offset_step / 4.0
                angles = np.arange(
                    best_angle - angle_step,
                    best_angle + angle_step + new_angle_step * 0.5,
                    new_angle_step,
                )
                offsets = np.arange(
                    best_offset - offset_step,
                    best_offset + offset_step + new_offset_step * 0.5,
                    new_offset_step,
                )
                best_score, best_angle, best_offset, stopped = self.search_grid(
                    angles,
                    offsets,
                    fixed_edge,
                    fixed_surface,
                    (best_score, best_angle, best_offset),
                    flipped=flipped,
                    iteration_limit=iteration_limit,
                )
                angle_step = new_angle_step
                offset_step = new_offset_step

            if best_score > global_best[0]:
                global_best = (best_score, best_angle, best_offset, flipped)

        best_score, best_angle, best_offset, best_flip = global_best
        self.angles[1] = ((best_angle + 180.0) % 360.0) - 180.0
        self.offsets[1] = best_offset
        self.axis_flips[1] = best_flip
        self.last_edge_score = float(best_score)
        self.search_completed = True
        match_found = best_score >= self.target_score
        reason = (
            f"tinta {self.target_score:g} atinsa"
            if match_found
            else f"tinta {self.target_score:g} nu a fost atinsa"
        )
        headline = (
            "POTRIVIRE EDGE GASITA" if match_found else "NICIO POTRIVIRE EDGE SIGURA"
        )
        status_color = "#7EE787" if match_found else "#FFD166"
        if self.simple_edge_interface:
            status_text = (
                f"ANALIZA TERMINATA\n"
                f"{self.search_iterations} pozitii testate"
            )
            status_color = UI_ACCENT
        else:
            status_text = (
                f"{headline}   •   {reason}\n"
                f"{self.search_iterations} iteratii   |   scor {best_score:.2f}/100"
            )
        self.plotter.add_text(
            status_text,
            position=(0.34, 0.89),
            viewport=True,
            font_size=8,
            color=status_color,
            font="arial",
            shadow=False,
            name="search_status",
        )
        print(
            f"Brute force: {self.search_iterations} iteratii, scor={best_score:.3f}, "
            f"flip={best_flip}, rotatie={self.angles[1]:.3f}, "
            f"deplasare={self.offsets[1]:.3f}"
        )
        self.update_scene(sync_controls=True)
        self.frame_geometry()
        self._search_running = False

    def automatic_auto_match(self):
        """Compara profilul vasului cu o eventuala imbinare fizica de muchii."""
        if not self._ready or self.max_iterations <= 0 or self._search_running:
            return

        self.selected_match_mode = "profile"
        self.angles[:] = [0.0, 0.0]
        self.offsets[:] = [0.0, 0.0]
        self.axis_flips[:] = [False, False]
        self.automatic_profile_match()
        profile_score = float(self.current_profile_metrics()[0])
        profile_iterations = self.search_iterations
        profile_state = (
            list(self.angles),
            list(self.offsets),
            list(self.axis_flips),
        )

        self.selected_match_mode = "edge"
        self.angles[:] = [0.0, 0.0]
        self.offsets[:] = [0.0, 0.0]
        self.axis_flips[:] = [False, False]
        self.automatic_edge_match()
        edge_score = float(self.last_edge_score)
        edge_iterations = self.search_iterations
        edge_state = (
            list(self.angles),
            list(self.offsets),
            list(self.axis_flips),
        )

        edge_acceptance = max(60.0, min(self.target_score, 80.0))
        if edge_score >= edge_acceptance:
            selected = "edge"
            selected_state = edge_state
            selected_score = edge_score
        else:
            selected = "profile"
            selected_state = profile_state
            selected_score = profile_score

        self.selected_match_mode = selected
        self.angles[:], self.offsets[:], self.axis_flips[:] = selected_state
        self.auto_scores = {"profile": profile_score, "edge": edge_score}
        self.search_iterations = profile_iterations + edge_iterations
        self.search_completed = True
        confidence_ok = selected_score >= self.target_score
        self.plotter.add_text(
            f"AUTO A ALES {selected.upper()}\n"
            f"profil {profile_score:.2f}/100   |   muchie {edge_score:.2f}/100\n"
            f"orientare ciob 2: {'inversata' if self.axis_flips[1] else 'normala'}",
            position=(0.34, 0.89),
            viewport=True,
            font_size=8,
            color="#7EE787" if confidence_ok else "#FFD166",
            font="arial",
            shadow=False,
            name="search_status",
        )
        print(
            f"Mod AUTO: selectat={selected}, profil={profile_score:.3f}, "
            f"edge={edge_score:.3f}, evaluari={self.search_iterations}"
        )
        self.update_scene(sync_controls=True)
        self.frame_geometry()

    def reset(self):
        self.angles[:] = [0.0, 0.0]
        self.offsets[:] = [0.0, 0.0]
        self.axis_flips[:] = [False, False]
        if self.match_mode == "auto":
            self.selected_match_mode = "profile"
            self.auto_scores = {"profile": None, "edge": None}
        self.search_iterations = 0
        self.search_completed = False
        self.update_scene(sync_controls=True)
        self.frame_geometry()

    def final_mesh(self, index):
        result = self.base_meshes[index].copy()
        result.vertices, _ = self.transform_points(index, self.base_vertices[index])
        return result

    def save_result(self):
        """Salveaza ambele cioburi transformate, ansamblul si parametrii."""
        os.makedirs(self.output_dir, exist_ok=True)
        meshes = [self.final_mesh(0), self.final_mesh(1)]
        shard_paths = [
            os.path.join(self.output_dir, "shard_1_aligned.obj"),
            os.path.join(self.output_dir, "shard_2_aligned.obj"),
        ]
        for mesh, path in zip(meshes, shard_paths):
            mesh.export(path)
        combined_path = os.path.join(self.output_dir, "matched_pair.obj")
        trimesh.util.concatenate(meshes).export(combined_path)

        active_mode = self.effective_match_mode()
        if active_mode == "profile":
            score, distance, slope_difference = self.current_profile_metrics()
            compatible_contacts = None
            contact_verdict = None
        else:
            points1, normals1 = self.current_geometry(0)
            points2, normals2 = self.current_geometry(1)
            edge1 = self.current_edge_geometry(0)
            edge2 = self.current_edge_geometry(1)
            score, distance, forward, backward = matching_score(
                *edge1,
                *edge2,
                self.contact_threshold,
                surface_geometry=(
                    points1,
                    normals1,
                    points2,
                    normals2,
                    max(self.thickness * 0.35, self.mesh_size * 0.006),
                ),
            )
            slope_difference = None
            compatible_contacts = forward["contacts"] + backward["contacts"]
            contact_verdict = self.edge_verdict(score, compatible_contacts)[0]
        report = {
            "input_meshes": self.mesh_paths,
            "thickness": self.thickness,
            "axis_origin": self.axis_origin.tolist(),
            "axis_direction": self.axis_direction.tolist(),
            "match_mode": self.match_mode,
            "selected_match_mode": active_mode,
            "auto_scores": self.auto_scores if self.match_mode == "auto" else None,
            "shards": [
                {
                    "rotation_degrees": self.angles[i],
                    "axial_offset": self.offsets[i],
                    "axis_flipped": self.axis_flips[i],
                }
                for i in range(2)
            ],
            "matching_score": score,
            "profile_or_distance_error": distance if np.isfinite(distance) else None,
            "profile_slope_difference": slope_difference,
            "compatible_contacts": compatible_contacts,
            "contact_verdict": contact_verdict,
            "contact_threshold": self.contact_threshold,
            "automatic_search": {
                "completed": self.search_completed,
                "iterations": self.search_iterations,
                "target_score": self.target_score,
                "maximum_iterations": self.max_iterations,
            },
        }
        report_path = os.path.join(self.output_dir, "match_report.json")
        with open(report_path, "w", encoding="utf-8") as stream:
            json.dump(report, stream, indent=2, ensure_ascii=False)
        message = f"Salvat in:\n{self.output_dir}"
        print(message)
        self.plotter.add_text(
            message,
            position=(0.73, 0.065),
            viewport=True,
            font_size=8,
            color="#7EE787",
            font="arial",
            shadow=False,
            name="saved_info",
        )
        self.plotter.render()


def parse_arguments():
    parser = argparse.ArgumentParser(
        description=(
            "Calculeaza cat de bine se potrivesc geometric doua cioburi "
            "pe profilul aceluiasi vas."
        )
    )
    parser.add_argument("--mesh1", required=True, help="Primul fisier OBJ; stabileste axa comuna.")
    parser.add_argument("--mesh2", required=True, help="Al doilea fisier OBJ.")
    parser.add_argument("--thickness", required=True, type=float, help="Grosimea comuna a cioburilor.")
    parser.add_argument(
        "--output-dir",
        default="pair_match_output",
        help="Folderul rezultatului salvat cu tasta S (implicit: pair_match_output).",
    )
    parser.add_argument(
        "--target-score",
        type=float,
        default=70.0,
        help=(
            "Prag orientativ pentru un scor bun (implicit: 70)."
        ),
    )
    parser.add_argument(
        "--max-iterations",
        type=int,
        default=1800,
        help="Numarul maxim de pozitii testate de brute force (implicit: 1800).",
    )
    parser.add_argument(
        "--manual-start",
        action="store_true",
        help="Nu porni automat cautarea; asteapta apasarea tastei A.",
    )
    parser.add_argument(
        "--match-mode",
        choices=("auto", "profile", "edge"),
        default="profile",
        help=argparse.SUPPRESS,
    )
    return parser.parse_args()


def main():
    arguments = parse_arguments()
    for path in (arguments.mesh1, arguments.mesh2):
        if not os.path.isfile(path):
            raise FileNotFoundError(f"Fisierul {path!r} nu a fost gasit.")
    PairMatchingApp(
        arguments.mesh1,
        arguments.mesh2,
        arguments.thickness,
        arguments.output_dir,
        arguments.target_score,
        arguments.max_iterations,
        auto_start=not arguments.manual_start,
        match_mode=arguments.match_mode,
    )


if __name__ == "__main__":
    main()
