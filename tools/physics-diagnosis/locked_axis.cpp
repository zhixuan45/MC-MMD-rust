// Standalone causal experiment. This subclass is never linked into the game.
#include "btBulletDynamicsCommon.h"
#include "BulletDynamics/ConstraintSolver/btGeneric6DofSpringConstraint.h"
#include <algorithm>
#include <cmath>
#include <iostream>

class ContinuousLockedAxes final : public btGeneric6DofSpringConstraint {
public:
    using btGeneric6DofSpringConstraint::btGeneric6DofSpringConstraint;
    void getInfo1(btConstraintInfo1* info) override {
        btGeneric6DofSpringConstraint::getInfo1(info);
        // A locked axis needs a velocity row even at exactly zero position error.
        auto* linear = getTranslationalLimitMotor();
        for (int i = 0; i < 3; ++i) {
            if (linear->m_lowerLimit[i] == linear->m_upperLimit[i] &&
                linear->m_currentLimit[i] == 0) {
                if (!linear->m_enableMotor[i]) {
                    ++info->m_numConstraintRows;
                    --info->nub;
                }
                linear->m_currentLimit[i] = 1;
                linear->m_currentLimitError[i] =
                    linear->m_currentLinearDiff[i] - linear->m_lowerLimit[i];
            }
        }
    }
};

void run(bool continuous, float hz) {
    btDefaultCollisionConfiguration collisionConfig;
    btCollisionDispatcher dispatcher(&collisionConfig);
    btDbvtBroadphase broadphase;
    btSequentialImpulseConstraintSolver solver;
    btDiscreteDynamicsWorld world(&dispatcher, &broadphase, &solver, &collisionConfig);
    world.setGravity(btVector3(0, -98, 0));
    btBoxShape shape(btVector3(0.3, 1.0, 0.025));
    btTransform rootPose, platePose;
    rootPose.setIdentity();
    platePose.setIdentity();
    rootPose.setOrigin(btVector3(0, 10, 0));
    platePose.setOrigin(btVector3(0, 8, 0));
    btDefaultMotionState rootState(rootPose), plateState(platePose);
    btVector3 inertia;
    shape.calculateLocalInertia(0.2, inertia);
    btRigidBody::btRigidBodyConstructionInfo rootInfo(0, &rootState, &shape);
    btRigidBody::btRigidBodyConstructionInfo plateInfo(0.2, &plateState, &shape, inertia);
    plateInfo.m_linearDamping = 0.5;
    plateInfo.m_angularDamping = 0.5;
    plateInfo.m_additionalDamping = true;
    btRigidBody root(rootInfo), plate(plateInfo);
    root.setCollisionFlags(root.getCollisionFlags() | btCollisionObject::CF_KINEMATIC_OBJECT);
    root.setActivationState(DISABLE_DEACTIVATION);
    plate.setActivationState(DISABLE_DEACTIVATION);
    world.addRigidBody(&root, 8, 0);
    world.addRigidBody(&plate, 8, 0);
    btTransform frameA, frameB;
    frameA.setIdentity();
    frameB.setIdentity();
    frameA.setOrigin(btVector3(0, -1, 0));
    frameB.setOrigin(btVector3(0, 1, 0));
    btGeneric6DofSpringConstraint base(root, plate, frameA, frameB, true);
    ContinuousLockedAxes candidate(root, plate, frameA, frameB, true);
    auto* joint = continuous ? static_cast<btGeneric6DofSpringConstraint*>(&candidate) : &base;
    joint->setLinearLowerLimit(btVector3(0, 0, 0));
    joint->setLinearUpperLimit(btVector3(0, 0, 0));
    joint->setAngularLowerLimit(btVector3(-0.6, -0.6, -0.6));
    joint->setAngularUpperLimit(btVector3(0.6, 0.6, 0.6));
    for (int i = 0; i < 6; ++i) {
        joint->setParam(BT_CONSTRAINT_STOP_ERP, 0.475, i);
        joint->enableSpring(i, i >= 3);
        if (i >= 3) joint->setStiffness(i, 16);
    }
    joint->setEquilibriumPoint();
    world.addConstraint(joint, true);
    btTypedConstraint::btConstraintInfo1 initialInfo;
    joint->getInfo1(&initialInfo);
    float maxDelta = 0;
    double totalDelta = 0;
    int samples = 0;
    auto previous = plate.getCenterOfMassPosition();
    for (int frame = 0; frame < int(hz * 10); ++frame) {
        world.stepSimulation(1.0f / hz, 5, 1.0f / hz);
        auto current = plate.getCenterOfMassPosition();
        const float delta = (current - previous).length();
        if (frame >= int(hz * 5)) {
            maxDelta = std::max(maxDelta, delta);
            totalDelta += delta;
            ++samples;
        }
        previous = current;
    }
    std::cout << "{\"case\":\"continuous_locked_rows\",\"candidate\":"
              << (continuous ? "true" : "false") << ",\"physics_hz\":" << hz
              << ",\"initial_rows\":" << initialInfo.m_numConstraintRows
              << ",\"max_delta\":" << maxDelta
              << ",\"mean_delta\":" << totalDelta / samples << "}\n";
    world.removeConstraint(joint);
    world.removeRigidBody(&plate);
    world.removeRigidBody(&root);
}

int main() {
    for (float hz : {60.f, 120.f}) {
        run(false, hz);
        run(true, hz);
    }
}
