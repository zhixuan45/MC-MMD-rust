use glam::{Vec2, Vec3};
use mmd_engine::model::{MmdModel, RuntimeVertex, VertexWeight};

#[test]
fn gpu_payload_keeps_sdef_as_explicit_bdef2_approximation() {
    let mut model = MmdModel::new();
    model.vertices = (0..4)
        .map(|_| RuntimeVertex {
            position: Vec3::ZERO,
            normal: Vec3::Y,
            uv: Vec2::ZERO,
        })
        .collect();
    model.weights = vec![
        VertexWeight::Bdef1 { bone: 4 },
        VertexWeight::Bdef2 {
            bones: [1, 3],
            weight: 0.25,
        },
        VertexWeight::Bdef4 {
            bones: [0, 1, 2, 3],
            weights: [0.1, 0.2, 0.3, 0.4],
        },
        VertexWeight::Sdef {
            bones: [1, 3],
            weight: 0.25,
            c: Vec3::new(100.0, 200.0, 300.0),
            r0: Vec3::new(-100.0, -200.0, -300.0),
            r1: Vec3::new(400.0, 500.0, 600.0),
        },
    ];

    model.init_gpu_skinning_data();

    let indices = model.get_bone_indices();
    let weights = model.get_bone_weights();
    assert_eq!(&indices[0..4], &[4, -1, -1, -1]);
    assert_eq!(&weights[0..4], &[1.0, 0.0, 0.0, 0.0]);
    assert_eq!(&indices[4..8], &[1, 3, -1, -1]);
    assert_eq!(&weights[4..8], &[0.25, 0.75, 0.0, 0.0]);
    assert_eq!(&indices[8..12], &[0, 1, 2, 3]);
    assert_eq!(&weights[8..12], &[0.1, 0.2, 0.3, 0.4]);
    assert_eq!(&indices[12..16], &indices[4..8]);
    assert_eq!(&weights[12..16], &weights[4..8]);
}
