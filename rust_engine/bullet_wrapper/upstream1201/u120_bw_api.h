/**
 * Bullet3 C Wrapper API
 *
 * 纯 C 接口封装 Bullet3 核心功能，供 Rust FFI 调用。
 * 仅包含 MMD 物理所需的最小 API 子集。
 */

#ifndef U120_BW_API_H
#define U120_BW_API_H

#include <stdbool.h>

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
} U120_BW_AllocStats;

/* 获取当前存活的 C++ 对象计数 */
U120_BW_AllocStats u120_bw_get_alloc_stats(void);

/* 不透明指针类型 */
typedef struct U120_BW_World U120_BW_World;
typedef struct U120_BW_Shape U120_BW_Shape;
typedef struct U120_BW_RigidBody U120_BW_RigidBody;
typedef struct U120_BW_Constraint U120_BW_Constraint;

/* Bullet3 激活状态常量 */
#define U120_BW_ACTIVE_TAG 1
#define U120_BW_ISLAND_SLEEPING 2
#define U120_BW_WANTS_DEACTIVATION 3
#define U120_BW_DISABLE_DEACTIVATION 4
#define U120_BW_DISABLE_SIMULATION 5

/* Bullet3 约束参数常量 */
#define U120_BW_CONSTRAINT_STOP_ERP 2
#define U120_BW_CONSTRAINT_STOP_CFM 3

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
    U120_BW_Shape* shape;
    float initial_transform[16]; /* 4x4 列主序 */
} U120_BW_RigidBodyInfo;

/* ===== 物理世界 ===== */
U120_BW_World* u120_bw_world_create(float gravity_x, float gravity_y, float gravity_z);
void u120_bw_world_destroy(U120_BW_World* world);
void u120_bw_world_step(U120_BW_World* world, float dt, int max_substeps, float fixed_dt);
void u120_bw_world_set_gravity(U120_BW_World* world, float x, float y, float z);
void u120_bw_world_add_rigid_body(U120_BW_World* world, U120_BW_RigidBody* rb, int group, int mask);
void u120_bw_world_remove_rigid_body(U120_BW_World* world, U120_BW_RigidBody* rb);
void u120_bw_world_add_constraint(U120_BW_World* world, U120_BW_Constraint* c, bool disable_collision);
void u120_bw_world_remove_constraint(U120_BW_World* world, U120_BW_Constraint* c);
void u120_bw_world_set_kinematic_filter(U120_BW_World* world, bool enabled);

/* ===== 碰撞形状 ===== */
U120_BW_Shape* u120_bw_shape_sphere(float radius);
U120_BW_Shape* u120_bw_shape_box(float hx, float hy, float hz);
U120_BW_Shape* u120_bw_shape_capsule(float radius, float height);
void u120_bw_shape_destroy(U120_BW_Shape* shape);

/* ===== 刚体 ===== */
U120_BW_RigidBody* u120_bw_rigid_body_create(const U120_BW_RigidBodyInfo* info);
void u120_bw_rigid_body_destroy(U120_BW_RigidBody* rb);
void u120_bw_rigid_body_get_transform(U120_BW_RigidBody* rb, float* matrix4x4);
void u120_bw_rigid_body_set_transform(U120_BW_RigidBody* rb, const float* matrix4x4);
void u120_bw_rigid_body_get_position(U120_BW_RigidBody* rb, float* x, float* y, float* z);
void u120_bw_rigid_body_get_rotation(U120_BW_RigidBody* rb, float* x, float* y, float* z, float* w);
void u120_bw_rigid_body_set_linear_velocity(U120_BW_RigidBody* rb, float x, float y, float z);
void u120_bw_rigid_body_set_angular_velocity(U120_BW_RigidBody* rb, float x, float y, float z);
void u120_bw_rigid_body_get_linear_velocity(U120_BW_RigidBody* rb, float* x, float* y, float* z);
void u120_bw_rigid_body_get_angular_velocity(U120_BW_RigidBody* rb, float* x, float* y, float* z);
void u120_bw_rigid_body_set_damping(U120_BW_RigidBody* rb, float linear, float angular);
void u120_bw_rigid_body_set_friction(U120_BW_RigidBody* rb, float friction);
void u120_bw_rigid_body_set_restitution(U120_BW_RigidBody* rb, float restitution);
void u120_bw_rigid_body_set_activation_state(U120_BW_RigidBody* rb, int state);
void u120_bw_rigid_body_force_activation_state(U120_BW_RigidBody* rb, int state);
void u120_bw_rigid_body_set_kinematic(U120_BW_RigidBody* rb, bool kinematic);
float u120_bw_rigid_body_get_mass(U120_BW_RigidBody* rb);
void u120_bw_rigid_body_clear_forces(U120_BW_RigidBody* rb);
void u120_bw_rigid_body_apply_central_force(U120_BW_RigidBody* rb, float x, float y, float z);

/* ===== 6DOF 弹簧约束 ===== */
U120_BW_Constraint* u120_bw_6dof_spring_create(
    U120_BW_RigidBody* a, U120_BW_RigidBody* b,
    const float* frame_a_4x4, const float* frame_b_4x4,
    bool use_linear_ref_a);
void u120_bw_constraint_destroy(U120_BW_Constraint* c);
void u120_bw_6dof_spring_set_linear_lower_limit(U120_BW_Constraint* c, float x, float y, float z);
void u120_bw_6dof_spring_set_linear_upper_limit(U120_BW_Constraint* c, float x, float y, float z);
void u120_bw_6dof_spring_set_angular_lower_limit(U120_BW_Constraint* c, float x, float y, float z);
void u120_bw_6dof_spring_set_angular_upper_limit(U120_BW_Constraint* c, float x, float y, float z);
void u120_bw_6dof_spring_enable_spring(U120_BW_Constraint* c, int index, bool on);
void u120_bw_6dof_spring_set_stiffness(U120_BW_Constraint* c, int index, float stiffness);
void u120_bw_6dof_spring_set_damping(U120_BW_Constraint* c, int index, float damping);
void u120_bw_6dof_spring_set_equilibrium_point(U120_BW_Constraint* c);
void u120_bw_6dof_spring_set_param(U120_BW_Constraint* c, int param, float value, int axis);
void u120_bw_6dof_spring_use_frame_offset(U120_BW_Constraint* c, bool on);

#ifdef __cplusplus
}
#endif

#endif /* U120_BW_API_H */
