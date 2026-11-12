import type { Request, Response } from "express";
import jwt from "jsonwebtoken";

export interface AdminSession {
  adminId: string;
  username: string;
  role: "admin" | "superadmin";
}

export function adminSessionFromRequest(req: Request): AdminSession | null {
  const token = req.cookies?.gr_admin_session as string | undefined;
  const secret = process.env.ADMIN_JWT_SECRET;
  if (!token || !secret) return null;

  try {
    const payload = jwt.verify(token, secret) as AdminSession;
    if (
      typeof payload.adminId !== "string" ||
      typeof payload.username !== "string" ||
      !["admin", "superadmin"].includes(payload.role)
    ) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

export function setAdminSessionCookie(
  res: Response,
  session: AdminSession,
): void {
  const secret = process.env.ADMIN_JWT_SECRET;
  if (!secret) throw new Error("ADMIN_JWT_SECRET is not configured.");
  const token = jwt.sign(session, secret, { expiresIn: "12h" });
  res.cookie("gr_admin_session", token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "strict",
    maxAge: 12 * 60 * 60 * 1000,
    path: "/",
  });
}
