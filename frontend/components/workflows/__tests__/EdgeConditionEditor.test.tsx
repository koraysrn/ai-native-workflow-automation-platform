import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import * as api from "../../../lib/api";
import EdgeConditionEditor from "../EdgeConditionEditor";

jest.mock("../../../lib/api", () => ({
  validateWorkflowExpression: jest.fn(),
}));

const validateWorkflowExpression = api.validateWorkflowExpression as jest.MockedFunction<
  typeof api.validateWorkflowExpression
>;

const closeSpy = jest.fn();
const duplicateSpy = jest.fn();

function Harness({ initial }: { initial: string }) {
  const [condition, setCondition] = useState(initial);
  return (
    <EdgeConditionEditor
      edge={{ id: "e1", source: "start", target: "end", data: { condition } }}
      variables={["amount", "role"]}
      onChangeCondition={setCondition}
      onClose={closeSpy}
      onDuplicate={duplicateSpy}
    />
  );
}

function typeIntoEditor(value: string): HTMLTextAreaElement {
  const textarea = screen.getByLabelText("Condition expression") as HTMLTextAreaElement;
  fireEvent.change(textarea, { target: { value } });
  textarea.setSelectionRange(value.length, value.length);
  fireEvent.select(textarea);
  return textarea;
}

beforeEach(() => {
  jest.resetAllMocks();
  validateWorkflowExpression.mockResolvedValue({ valid: true, errors: [] });
});

describe("EdgeConditionEditor", () => {
  it("renders an inline field error for a malformed expression without calling the backend", () => {
    render(<Harness initial="#amount > (100" />);

    expect(screen.getByRole("alert")).toHaveTextContent("Expression is invalid");
    expect(screen.getByLabelText("Condition expression")).toHaveAttribute("aria-invalid", "true");
    expect(validateWorkflowExpression).not.toHaveBeenCalled();
  });

  it("validates a well-formed expression with the backend and shows the valid state", async () => {
    render(<Harness initial="#amount > 100" />);

    expect(await screen.findByText("Expression is valid.", {}, { timeout: 2000 })).toBeVisible();
    expect(validateWorkflowExpression).toHaveBeenCalledWith("#amount > 100");
  });

  it("renders backend field errors inline", async () => {
    validateWorkflowExpression.mockResolvedValue({
      valid: false,
      errors: [{ field: "expression", message: "Expression contains a forbidden pattern: System" }],
    });
    render(<Harness initial="#amount > 100" />);

    expect(
      await screen.findByText(
        "Expression contains a forbidden pattern: System",
        {},
        { timeout: 2000 },
      ),
    ).toBeVisible();
  });

  it("shows retry feedback when validation fails and retries on demand", async () => {
    validateWorkflowExpression.mockRejectedValueOnce(new Error("offline"));
    render(<Harness initial="#amount > 100" />);

    expect(await screen.findByText(/Validation failed\. Check your connection/, {}, { timeout: 2000 }))
      .toBeVisible();
    expect(validateWorkflowExpression).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Retry validation" }));

    await waitFor(() => expect(validateWorkflowExpression).toHaveBeenCalledTimes(2), {
      timeout: 2000,
    });
  });

  it("inserts a workflow variable from autocomplete", () => {
    render(<Harness initial="" />);

    fireEvent.click(screen.getByRole("button", { name: "Show condition suggestions" }));
    const suggestions = screen.getByRole("listbox", { name: "Condition suggestions" });
    fireEvent.click(within(suggestions).getByRole("option", { name: /#amount/ }));

    expect(screen.getByLabelText("Condition expression")).toHaveValue("#amount");
  });

  it("inserts a whitelisted function from autocomplete", () => {
    render(<Harness initial="" />);

    typeIntoEditor("#f");
    fireEvent.click(screen.getByRole("button", { name: "Show condition suggestions" }));
    const suggestions = screen.getByRole("listbox", { name: "Condition suggestions" });
    fireEvent.click(within(suggestions).getByRole("option", { name: /#fn\.matches/ }));

    expect(screen.getByLabelText("Condition expression")).toHaveValue(
      "#fn.matches(#code, '^[A-Z]+$')",
    );
  });

  it("builds a hasRole expression through the builder", () => {
    render(<Harness initial="" />);

    fireEvent.change(screen.getByLabelText("Variable"), { target: { value: "role" } });
    fireEvent.change(screen.getByLabelText("Operator"), { target: { value: "hasRole" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "MANAGER" } });
    fireEvent.click(screen.getByRole("button", { name: "Insert condition" }));

    expect(screen.getByLabelText("Condition expression")).toHaveValue(
      "#fn.hasRole(#role, 'MANAGER')",
    );
  });

  it("builds a numeric comparison through the builder", () => {
    render(<Harness initial="" />);

    fireEvent.change(screen.getByLabelText("Operator"), { target: { value: ">" } });
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "100" } });
    fireEvent.click(screen.getByRole("button", { name: "Insert condition" }));

    expect(screen.getByLabelText("Condition expression")).toHaveValue("#amount > 100");
  });

  it("closes the inspector on Escape and duplicates on demand", () => {
    render(<Harness initial="#amount > 100" />);

    fireEvent.keyDown(screen.getByLabelText("Condition expression"), { key: "Escape" });
    expect(closeSpy).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Duplicate conditional edge" }));
    expect(duplicateSpy).toHaveBeenCalledTimes(1);
  });

  it("keeps special characters in the textarea without rendering markup", () => {
    const { container } = render(<Harness initial={"#name == '<img src=x onerror=alert(1)>'"} />);

    expect(screen.getByLabelText("Condition expression")).toHaveValue(
      "#name == '<img src=x onerror=alert(1)>'",
    );
    expect(container.querySelector("img")).toBeNull();
  });
});
