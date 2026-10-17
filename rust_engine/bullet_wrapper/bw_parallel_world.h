#pragma once
#include "bw_api.h"
#include "BulletDynamics/Dynamics/btDiscreteDynamicsWorld.h"
#include "BulletCollision/CollisionDispatch/btCollisionDispatcherMt.h"

class btConstraintSolverPoolMt;
class btSequentialImpulseConstraintSolverMt;

// 关闭内部并行时保留原 dispatcher 的流形维护和顺序求解。
class BW_ParallelDispatcher final : public btCollisionDispatcherMt {
public:
    bool parallel = false;
    explicit BW_ParallelDispatcher(btCollisionConfiguration* config);
    void dispatchAllCollisionPairs(btOverlappingPairCache*, const btDispatcherInfo&, btDispatcher*) override;
};

btCollisionDispatcher* bw_make_dispatcher(btCollisionConfiguration* config);

class BW_ParallelWorld : public btDiscreteDynamicsWorld {
    btConstraintSolverPoolMt* pool = nullptr;
    btSequentialImpulseConstraintSolverMt* parallelSolver = nullptr;
    bool parallel = false;
    BW_ParallelStats stats{};

protected:
    void solveConstraints(btContactSolverInfo& info) override;
    void predictUnconstraintMotion(btScalar dt) override;

public:
    BW_ParallelWorld(btDispatcher*, btBroadphaseInterface*, btConstraintSolver*, btCollisionConfiguration*);
    ~BW_ParallelWorld() override;
    bool prepareParallel();
    void setParallel(bool enabled);
    bool worthwhile() const;
    void resetParallelStats();
    void finishParallelStats();
    BW_ParallelStats parallelStats() const { return stats; }
};
