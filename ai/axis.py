import argparse
import os

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


if __name__ == "__main__":
    main()
