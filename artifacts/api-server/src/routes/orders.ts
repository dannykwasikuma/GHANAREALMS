import { Router, type IRouter } from "express";
import {
  LookupOrderQueryParams,
  LookupOrderResponse,
  StartCheckoutBody,
  VerifyPaymentQueryParams,
  VerifyPaymentResponse,
} from "@workspace/api-zod";
import { grQueryOne } from "../lib/ghanarealms-db";

const router: IRouter = Router();

router.get("/lookup", async (req, res): Promise<void> => {
  const parsed = LookupOrderQueryParams.safeParse(req.query);
  if (!parsed.success) {
    res.status(400).json({ error: "Missing or invalid order number." });
    return;
  }

  try {
    const order = await grQueryOne<{
      order_number: string;
      status: string;
      created_at: Date | string;
      minecraft_username: string;
      product_name: string;
    }>(
      `SELECT o.order_number, o.status, o.created_at,
              mp.username AS minecraft_username, p.name AS product_name
       FROM orders o
       JOIN minecraft_players mp ON mp.id = o.minecraft_player_id
       JOIN order_items oi ON oi.order_id = o.id
       JOIN products p ON p.id = oi.product_id
       WHERE o.order_number = $1 LIMIT 1`,
      [parsed.data.order],
    );
    if (!order) {
      res.status(404).json({ error: "Order not found." });
      return;
    }

    const delivery = await grQueryOne<{ status: string }>(
      `SELECT dq.status FROM delivery_queue dq
       JOIN orders o ON o.id = dq.order_id
       WHERE o.order_number = $1 LIMIT 1`,
      [order.order_number],
    );
    res.json(
      LookupOrderResponse.parse({
        orderNumber: order.order_number,
        product: order.product_name,
        minecraftUsername: order.minecraft_username,
        paymentStatus: order.status,
        deliveryStatus: delivery?.status ?? "N/A",
        purchaseDate:
          order.created_at instanceof Date
            ? order.created_at.toISOString()
            : order.created_at,
      }),
    );
  } catch (err) {
    req.log.warn({ err }, "Order lookup unavailable");
    res.status(503).json({ error: "Order lookup is temporarily unavailable." });
  }
});

router.post("/checkout/init", async (req, res): Promise<void> => {
  const parsed = StartCheckoutBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid checkout request." });
    return;
  }

  // The latest upload requires the signed player session and linked-account
  // helpers, but those source files and their migrations were not provided.
  // Fail closed until that implementation is present; never accept a username
  // supplied by the browser as proof of Minecraft identity.
  res.status(503).json({
    error:
      "Secure player sign-in and Minecraft account linking are not configured yet.",
  });
});

router.get("/payments/verify", async (req, res): Promise<void> => {
  const parsed = VerifyPaymentQueryParams.safeParse(req.query);
  if (!parsed.success) {
    res.status(400).json({ error: "Missing payment reference." });
    return;
  }

  try {
    const payment = await grQueryOne<{
      status: string;
      order_id: string;
    }>(
      "SELECT status, order_id FROM payments WHERE paystack_reference = $1",
      [parsed.data.reference],
    );
    if (!payment) {
      res.json(VerifyPaymentResponse.parse({ state: "UNKNOWN", orderNumber: null }));
      return;
    }

    const order = await grQueryOne<{ order_number: string }>(
      "SELECT order_number FROM orders WHERE id = $1",
      [payment.order_id],
    );
    const state =
      payment.status === "PAID"
        ? "PAID"
        : payment.status === "FAILED"
          ? "FAILED"
          : payment.status === "ABANDONED"
            ? "CANCELLED"
            : "PENDING";
    res.json(
      VerifyPaymentResponse.parse({
        state,
        orderNumber: order?.order_number ?? null,
      }),
    );
  } catch (err) {
    req.log.warn({ err }, "Payment status unavailable");
    res.status(503).json({ error: "Payment status is temporarily unavailable." });
  }
});

export default router;
