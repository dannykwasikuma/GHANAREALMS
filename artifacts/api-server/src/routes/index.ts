import { Router, type IRouter } from "express";
import adminRouter from "./admin";
import bridgeRouter from "./bridge";
import healthRouter from "./health";
import ordersRouter from "./orders";
import storeRouter from "./store";

const router: IRouter = Router();

router.use(healthRouter);
router.use(storeRouter);
router.use(ordersRouter);
router.use(adminRouter);
router.use(bridgeRouter);

export default router;
