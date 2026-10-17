#pragma once
#include <vector>
#include "bw_parallel_world.h"
#include "BulletDynamics/Dynamics/btRigidBody.h"
#include "LinearMath/btTransformUtil.h"

class BW_KinematicStepWorld final : public BW_ParallelWorld {
    struct KinematicTrack {
        btRigidBody* body;
        btTransform start;
        btTransform target;
    };

    std::vector<KinematicTrack> kinematicTracks;
    btScalar batchDuration = 0;
    btScalar elapsedDuration = 0;

    static void constrainRenderPrediction(
        btRigidBody* body, const btManifoldPoint& point, const btVector3& normal, bool isBodyA) {
        if (!body || body->isStaticOrKinematicObject() || !body->getMotionState()) {
            return;
        }

        const btTransform& solved = body->getCenterOfMassTransform();
        btTransform predicted;
        body->getMotionState()->getWorldTransform(predicted);
        const btVector3 worldPoint = isBodyA
            ? point.getPositionWorldOnA()
            : point.getPositionWorldOnB();
        const btVector3 localPoint = solved.invXform(worldPoint);
        const btVector3 rotationDelta =
            predicted.getBasis() * localPoint - solved.getBasis() * localPoint;
        if (rotationDelta.dot(normal) < -SIMD_EPSILON) {
            predicted.setBasis(solved.getBasis());
        }

        btVector3 translation = predicted.getOrigin() - solved.getOrigin();
        const btScalar inward = translation.dot(normal);
        if (inward < 0.0f) {
            translation -= normal * inward;
            predicted.setOrigin(solved.getOrigin() + translation);
        }
        body->getMotionState()->setWorldTransform(predicted);
    }

    static bool predictionMovesIntoContact(
        btRigidBody* body, const btManifoldPoint& point, const btVector3& normal, bool isBodyA) {
        if (!body || body->isStaticOrKinematicObject() || !body->getMotionState()) {
            return false;
        }
        const btTransform& solved = body->getCenterOfMassTransform();
        btTransform predicted;
        body->getMotionState()->getWorldTransform(predicted);
        const btVector3 worldPoint = isBodyA
            ? point.getPositionWorldOnA()
            : point.getPositionWorldOnB();
        const btVector3 localPoint = solved.invXform(worldPoint);
        const btVector3 pointDelta = predicted * localPoint - worldPoint;
        return pointDelta.dot(normal) < -SIMD_EPSILON;
    }

public:
    using BW_ParallelWorld::BW_ParallelWorld;

    btScalar renderTimeOffset() const {
        if (m_fixedTimeStep <= 0 || m_localTime < 0 || m_localTime >= m_fixedTimeStep) {
            return 0;
        }
        return m_localTime;
    }

    void syncRenderStates() {
        const btScalar offset = renderTimeOffset();
        for (int i = 0; i < m_nonStaticRigidBodies.size(); ++i) {
            btRigidBody* body = m_nonStaticRigidBodies[i];
            if (body->isStaticOrKinematicObject() || !body->getMotionState()) {
                continue;
            }

            const btTransform& solved = body->getCenterOfMassTransform();
            if (offset <= SIMD_EPSILON) {
                body->getMotionState()->setWorldTransform(solved);
                continue;
            }

            btTransform predicted;
            btTransformUtil::integrateTransform(
                solved, body->getLinearVelocity(), body->getAngularVelocity(), offset, predicted);
            body->getMotionState()->setWorldTransform(predicted);
        }

        // 接触约束只扫流形，避免按刚体重复遍历全世界。
        for (int pass = 0; pass < 4; ++pass) {
            for (int i = 0; i < getDispatcher()->getNumManifolds(); ++i) {
                const btPersistentManifold* manifold = getDispatcher()->getManifoldByIndexInternal(i);
                btRigidBody* bodyA = btRigidBody::upcast(
                    const_cast<btCollisionObject*>(manifold->getBody0()));
                btRigidBody* bodyB = btRigidBody::upcast(
                    const_cast<btCollisionObject*>(manifold->getBody1()));
                for (int j = 0; j < manifold->getNumContacts(); ++j) {
                    const btManifoldPoint& point = manifold->getContactPoint(j);
                    if (point.getDistance() > 0.0f) {
                        continue;
                    }
                    constrainRenderPrediction(bodyA, point, point.m_normalWorldOnB, true);
                    constrainRenderPrediction(bodyB, point, -point.m_normalWorldOnB, false);
                }
            }
        }

        // 斜向法线反复投影仍冲突时，退回真实解算姿态。
        for (int i = 0; i < getDispatcher()->getNumManifolds(); ++i) {
            const btPersistentManifold* manifold = getDispatcher()->getManifoldByIndexInternal(i);
            btRigidBody* bodyA = btRigidBody::upcast(
                const_cast<btCollisionObject*>(manifold->getBody0()));
            btRigidBody* bodyB = btRigidBody::upcast(
                const_cast<btCollisionObject*>(manifold->getBody1()));
            for (int j = 0; j < manifold->getNumContacts(); ++j) {
                const btManifoldPoint& point = manifold->getContactPoint(j);
                if (point.getDistance() > 0.0f) {
                    continue;
                }
                if (predictionMovesIntoContact(bodyA, point, point.m_normalWorldOnB, true)) {
                    bodyA->getMotionState()->setWorldTransform(bodyA->getCenterOfMassTransform());
                }
                if (predictionMovesIntoContact(bodyB, point, -point.m_normalWorldOnB, false)) {
                    bodyB->getMotionState()->setWorldTransform(bodyB->getCenterOfMassTransform());
                }
            }
        }
    }

protected:
    void saveKinematicState(btScalar timeStep) override {
        kinematicTracks.clear();
        batchDuration = timeStep;
        elapsedDuration = 0;

        for (int i = 0; i < m_collisionObjects.size(); ++i) {
            btRigidBody* body = btRigidBody::upcast(m_collisionObjects[i]);
            if (!body || body->getActivationState() == ISLAND_SLEEPING || !body->isKinematicObject()) {
                continue;
            }

            btMotionState* motionState = body->getMotionState();
            if (!motionState) {
                // 无 MotionState 时保留 Bullet 的原始端点速度计算。
                body->saveKinematicState(timeStep);
                continue;
            }

            btTransform target;
            motionState->getWorldTransform(target);
            kinematicTracks.push_back({body, body->getWorldTransform(), target});
        }
    }

    void internalSingleStepSimulation(btScalar timeStep) override {
        if (batchDuration > btScalar(0) && !kinematicTracks.empty()) {
            elapsedDuration = btMin(elapsedDuration + timeStep, batchDuration);
            const btScalar fraction = elapsedDuration / batchDuration;
            for (const KinematicTrack& track : kinematicTracks) {
                btMotionState* motionState = track.body->getMotionState();
                if (!motionState) {
                    continue;
                }

                btTransform pose;
                pose.setOrigin(track.start.getOrigin().lerp(track.target.getOrigin(), fraction));
                pose.setRotation(track.start.getRotation().slerp(track.target.getRotation(), fraction));
                motionState->setWorldTransform(pose);
                track.body->saveKinematicState(timeStep);
            }
        }

        btDiscreteDynamicsWorld::internalSingleStepSimulation(timeStep);
        if (elapsedDuration >= batchDuration) {
            kinematicTracks.clear();
        }
    }
};

