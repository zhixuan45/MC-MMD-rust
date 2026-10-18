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

    static btTransform renderTransform(btRigidBody* body) {
        btTransform transform = body->getCenterOfMassTransform();
        if (body->getMotionState()) {
            body->getMotionState()->getWorldTransform(transform);
        }
        return transform;
    }

    static btVector3 renderContactDelta(btRigidBody* body, const btVector3& worldPoint) {
        if (!body) {
            return btVector3(0, 0, 0);
        }
        const btTransform& solved = body->getCenterOfMassTransform();
        const btVector3 localPoint = solved.invXform(worldPoint);
        return renderTransform(body) * localPoint - worldPoint;
    }

    static btScalar relativeContactDelta(
        btRigidBody* bodyA, btRigidBody* bodyB, const btManifoldPoint& point) {
        const btVector3 deltaA = renderContactDelta(bodyA, point.getPositionWorldOnA());
        const btVector3 deltaB = renderContactDelta(bodyB, point.getPositionWorldOnB());
        return (deltaA - deltaB).dot(point.m_normalWorldOnB);
    }

    static void constrainRenderPrediction(
        btRigidBody* bodyA, btRigidBody* bodyB, const btManifoldPoint& point) {
        const btScalar relativeDelta = relativeContactDelta(bodyA, bodyB, point);
        if (relativeDelta >= -SIMD_EPSILON) {
            return;
        }

        const btScalar inverseMassA = bodyA ? bodyA->getInvMass() : 0;
        const btScalar inverseMassB = bodyB ? bodyB->getInvMass() : 0;
        const btScalar inverseMassSum = inverseMassA + inverseMassB;
        if (inverseMassSum <= SIMD_EPSILON) {
            return;
        }

        // 按逆质量分摊净内侵，保留两侧预测旋转。
        const btVector3 correction =
            point.m_normalWorldOnB * (-relativeDelta / inverseMassSum);
        if (inverseMassA > 0 && bodyA->getMotionState()) {
            btTransform predicted = renderTransform(bodyA);
            predicted.setOrigin(predicted.getOrigin() + correction * inverseMassA);
            bodyA->getMotionState()->setWorldTransform(predicted);
        }
        if (inverseMassB > 0 && bodyB->getMotionState()) {
            btTransform predicted = renderTransform(bodyB);
            predicted.setOrigin(predicted.getOrigin() - correction * inverseMassB);
            bodyB->getMotionState()->setWorldTransform(predicted);
        }
    }

    static bool predictionMovesIntoContact(
        btRigidBody* bodyA, btRigidBody* bodyB, const btManifoldPoint& point) {
        return relativeContactDelta(bodyA, bodyB, point) < -SIMD_EPSILON;
    }

    static void rollbackRenderPose(btRigidBody* body) {
        if (!body || body->getInvMass() <= 0 || !body->getMotionState()) {
            return;
        }
        body->getMotionState()->setWorldTransform(body->getCenterOfMassTransform());
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
                    constrainRenderPrediction(bodyA, bodyB, point);
                }
            }
        }

        // 多接触无法仅靠平移满足时，恢复参与动态体的求解姿态。
        bool rolledBack = false;
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
                if (predictionMovesIntoContact(bodyA, bodyB, point)) {
                    rollbackRenderPose(bodyA);
                    rollbackRenderPose(bodyB);
                    rolledBack = true;
                }
            }
        }
        if (rolledBack) {
            for (int pass = 0; pass < 4; ++pass) {
                for (int i = 0; i < getDispatcher()->getNumManifolds(); ++i) {
                    const btPersistentManifold* manifold =
                        getDispatcher()->getManifoldByIndexInternal(i);
                    btRigidBody* bodyA = btRigidBody::upcast(
                        const_cast<btCollisionObject*>(manifold->getBody0()));
                    btRigidBody* bodyB = btRigidBody::upcast(
                        const_cast<btCollisionObject*>(manifold->getBody1()));
                    for (int j = 0; j < manifold->getNumContacts(); ++j) {
                        const btManifoldPoint& point = manifold->getContactPoint(j);
                        if (point.getDistance() <= 0.0f) {
                            constrainRenderPrediction(bodyA, bodyB, point);
                        }
                    }
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

