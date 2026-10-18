/**
 * Bullet3 C Wrapper API
 * 
 * 纯 C 接口封装 Bullet3 核心功能，供 Rust FFI 调用。
 * 仅包含 MMD 物理所需的最小 API 子集。
 */

#ifndef BW_API_H
#define BW_API_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ===== 分配计数器（调试用） ===== */
typedef struct {
    int worlds;
    int shapes;
    int rigid_bodies;
    int constraints;
    int motion_states;
} BW_AllocStats;

/* 获取当前存活的 C++ 对象计数 */
BW_AllocStats bw_get_alloc_stats(void);

/* 不透明指针类型 */
typedef struct BW_World BW_World;
typedef struct BW_Shape BW_Shape;
typedef struct BW_RigidBody BW_RigidBody;
typedef struct BW_Constraint BW_Constraint;

/* 当前求解步中的真实穿透接触，用于只读诊断。 */
typedef struct {
    BW_RigidBody* body_a;
    BW_RigidBody* body_b;
    int contact_count;
    float max_penetration_depth;
    float max_applied_impulse;
    float total_applied_impulse;
    float point_a[3];
    float point_b[3];
    float normal_on_b[3];
} BW_ContactManifold;

/* 单条穿透接触；各字段来自同一个 btManifoldPoint。 */
typedef struct {
    BW_RigidBody* body_a;
    BW_RigidBody* body_b;
    float point_a[3];
    float point_b[3];
    float normal_on_b[3];
    float distance;
    float applied_normal_impulse;
} BW_ContactPoint;

typedef struct {
    uint64_t step_serial;
    int substeps; /* 实际执行数 */
    int requested_substeps; /* Bullet stepSimulation 的返回值，限幅前 */
    int phase; /* 0=尚无采样更新，1=碰撞检测后，2=物理步后 */
} BW_ContactSampleInfo;

/* Bullet 世界级接触求解设置，仅供显式诊断实验使用。 */
typedef struct {
    int iterations;
    float contact_erp;
    bool split_impulse;
    float split_impulse_penetration_threshold;
} BW_SolverSettings;

/* Constant-time telemetry; does not enumerate contacts or advance simulation. */
void bw_world_get_step_info(BW_World* world, BW_ContactSampleInfo* info);

#define BW_MAX_CONTACT_QUERY_TARGETS 256
#define BW_MAX_CONTACT_QUERY_CAPACITY 4096

/* 完整回读 Bullet 实际持有的 6DOF 参数，避免由 Rust 侧公式自证。 */
typedef struct {
    float linear_position[3];
    float angular_position[3];
    float linear_violation[3];
    float angular_violation[3];
    float frame_a[16];
    float frame_b[16];
    float linear_lower[3];
    float linear_upper[3];
    float angular_lower[3];
    float angular_upper[3];
    float stiffness[6];
    float damping[6];
    float equilibrium[6];
    int spring_enabled[6];
    int use_frame_offset;
} BW_ConstraintDiagnostic;

/* Bullet3 激活状态常量 */
#define BW_ACTIVE_TAG 1
#define BW_ISLAND_SLEEPING 2
#define BW_WANTS_DEACTIVATION 3
#define BW_DISABLE_DEACTIVATION 4
#define BW_DISABLE_SIMULATION 5

/* Bullet3 约束参数常量 */
#define BW_CONSTRAINT_STOP_ERP 2
#define BW_CONSTRAINT_STOP_CFM 3

/* ===== 刚体构建信息 ===== */
typedef struct {
    float mass;
    float linear_damping;
    float angular_damping;
    float friction;
    float restitution;
    bool additional_damping;
    bool is_kinematic;
    bool disable_deactivation;
    bool no_contact_response;
    BW_Shape* shape;
    float initial_transform[16]; /* 4x4 列主序 */
} BW_RigidBodyInfo;

/* ===== 物理世界 ===== */
BW_World* bw_world_create(float gravity_x, float gravity_y, float gravity_z);
void bw_world_destroy(BW_World* world);
bool bw_world_get_solver_settings(BW_World* world, BW_SolverSettings* output);
bool bw_world_set_solver_settings(BW_World* world, const BW_SolverSettings* settings);
void bw_world_step(BW_World* world, float dt, int max_substeps, float fixed_dt);
typedef struct BW_ParallelStats {
    int thread_width;
    int peak_active;
    int jobs;
    int islands;
    int max_island_bodies;
    int max_island_constraints;
} BW_ParallelStats;
bool bw_parallel_available(void);
int bw_world_step_parallel(BW_World* world, float dt, int max_substeps, float fixed_dt,
                          int thread_budget, bool parallel);
void bw_world_get_parallel_stats(BW_World* world, BW_ParallelStats* output);
float bw_world_get_render_time_offset(BW_World* world);
void bw_world_sync_render_states(BW_World* world);
/* 仅更新宽相/窄相接触，不推进时间、不执行约束或接触求解。 */
void bw_world_detect_collisions(BW_World* world);
/* 更改碰撞过滤后释放旧窄相算法与流形，避免继续求解旧接触。 */
void bw_world_refresh_body_collision_filter(BW_World* world, BW_RigidBody* rb);
void bw_world_set_gravity(BW_World* world, float x, float y, float z);
void bw_world_add_rigid_body(BW_World* world, BW_RigidBody* rb, int group, int mask);
void bw_world_remove_rigid_body(BW_World* world, BW_RigidBody* rb);
void bw_world_add_constraint(BW_World* world, BW_Constraint* c, bool disable_collision);
void bw_world_remove_constraint(BW_World* world, BW_Constraint* c);
void bw_world_set_kinematic_filter(BW_World* world, bool enabled);
int bw_world_get_contact_manifold_count(BW_World* world);
int bw_world_copy_contact_manifolds(
    BW_World* world, BW_ContactManifold* output, int capacity);
/* 有目标时仅查询涉及目标的点；否则查询全世界穿透点。容量受上述常量限制。 */
int bw_world_copy_contact_points(
    BW_World* world,
    BW_RigidBody* const* target_bodies, int target_count,
    BW_ContactPoint* output, int capacity,
    int* total_hits, BW_ContactSampleInfo* sample_info);

/* ===== 碰撞形状 ===== */
BW_Shape* bw_shape_sphere(float radius);
BW_Shape* bw_shape_box(float hx, float hy, float hz);
BW_Shape* bw_shape_capsule(float radius, float height);
void bw_shape_destroy(BW_Shape* shape);

/* ===== 刚体 ===== */
BW_RigidBody* bw_rigid_body_create(const BW_RigidBodyInfo* info);
void bw_rigid_body_destroy(BW_RigidBody* rb);
void bw_rigid_body_get_transform(BW_RigidBody* rb, float* matrix4x4);
void bw_rigid_body_get_simulation_transform(BW_RigidBody* rb, float* matrix4x4);
void bw_rigid_body_set_transform(BW_RigidBody* rb, const float* matrix4x4);
void bw_rigid_body_set_kinematic_target(BW_RigidBody* rb, const float* matrix4x4);
void bw_rigid_body_get_position(BW_RigidBody* rb, float* x, float* y, float* z);
void bw_rigid_body_get_rotation(BW_RigidBody* rb, float* x, float* y, float* z, float* w);
void bw_rigid_body_set_linear_velocity(BW_RigidBody* rb, float x, float y, float z);
void bw_rigid_body_set_angular_velocity(BW_RigidBody* rb, float x, float y, float z);
void bw_rigid_body_get_linear_velocity(BW_RigidBody* rb, float* x, float* y, float* z);
void bw_rigid_body_get_angular_velocity(BW_RigidBody* rb, float* x, float* y, float* z);
void bw_rigid_body_set_damping(BW_RigidBody* rb, float linear, float angular);
void bw_rigid_body_set_friction(BW_RigidBody* rb, float friction);
void bw_rigid_body_set_restitution(BW_RigidBody* rb, float restitution);
void bw_rigid_body_set_activation_state(BW_RigidBody* rb, int state);
void bw_rigid_body_force_activation_state(BW_RigidBody* rb, int state);
void bw_rigid_body_set_kinematic(BW_RigidBody* rb, bool kinematic);
float bw_rigid_body_get_mass(BW_RigidBody* rb);
void bw_rigid_body_clear_forces(BW_RigidBody* rb);
void bw_rigid_body_apply_central_force(BW_RigidBody* rb, float x, float y, float z);
/* 在质心外施力，rel_pos 为相对质心的世界坐标，用于产生可控力矩。 */
void bw_rigid_body_apply_force_at_point(
    BW_RigidBody* rb,
    float fx, float fy, float fz,
    float rel_x, float rel_y, float rel_z);
void bw_rigid_body_set_ignore_collision_check(
    BW_RigidBody* rb, BW_RigidBody* other, bool ignore);
bool bw_rigid_body_check_collide_with(BW_RigidBody* rb, BW_RigidBody* other);

/* ===== 6DOF 弹簧约束 ===== */
BW_Constraint* bw_6dof_spring_create(
    BW_RigidBody* a, BW_RigidBody* b,
    const float* frame_a_4x4, const float* frame_b_4x4,
    bool use_linear_ref_a);
void bw_constraint_destroy(BW_Constraint* c);
void bw_6dof_spring_set_linear_lower_limit(BW_Constraint* c, float x, float y, float z);
void bw_6dof_spring_set_linear_upper_limit(BW_Constraint* c, float x, float y, float z);
void bw_6dof_spring_set_angular_lower_limit(BW_Constraint* c, float x, float y, float z);
void bw_6dof_spring_set_angular_upper_limit(BW_Constraint* c, float x, float y, float z);
void bw_6dof_spring_enable_spring(BW_Constraint* c, int index, bool on);
void bw_6dof_spring_set_stiffness(BW_Constraint* c, int index, float stiffness);
void bw_6dof_spring_set_damping(BW_Constraint* c, int index, float damping);
void bw_6dof_spring_set_equilibrium_point(BW_Constraint* c);
void bw_6dof_spring_set_param(BW_Constraint* c, int param, float value, int axis);
void bw_6dof_spring_use_frame_offset(BW_Constraint* c, bool on);
bool bw_6dof_spring_get_diagnostic(
    BW_Constraint* c, BW_ConstraintDiagnostic* output);

#ifdef __cplusplus
}
#endif

#endif /* BW_API_H */
