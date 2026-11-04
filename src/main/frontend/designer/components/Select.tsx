import { useEffect, useRef, useState } from "react";

/**
 * Custom select component using Jenkins' own styling (.jenkins-select + .jenkins-dropdown).
 * React version for use in the designer (TSX). The vanilla JS version is in enhancer/customSelect.ts.
 */

export interface SelectOption {
  value: string;
  label: string;
}

interface SelectProps {
  id?: string;
  className?: string;
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
}

export function Select({ id, className = "", value, options, onChange, placeholder, disabled }: SelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const selected = options.find((o) => o.value === value);
  const displayLabel = selected ? selected.label : placeholder || "";

  useEffect(() => {
    if (!isOpen) return;
    const onClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, [isOpen]);

  const handleSelect = (v: string) => {
    setIsOpen(false);
    if (v !== value) {
      onChange(v);
    }
    btnRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!isOpen) setIsOpen(true);
    } else if (e.key === "Escape" && isOpen) {
      e.preventDefault();
      e.stopPropagation();
      setIsOpen(false);
      btnRef.current?.focus();
    }
  };

  return (
    <div
      ref={containerRef}
      className={`jenkins-select bpe-select ${isOpen ? "bpe-select--open" : ""} ${disabled ? "bpe-select--disabled" : ""} ${className}`}
    >
      <button
        ref={btnRef}
        id={id}
        type="button"
        className="jenkins-select__input bpe-select__trigger"
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        onClick={() => !disabled && setIsOpen(!isOpen)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
      >
        <span className={`bpe-select__label ${!selected ? "bpe-select__label--placeholder" : ""}`}>{displayLabel}</span>
      </button>
      {isOpen && (
        <div ref={menuRef} className="bpe-select__menu" role="listbox">
          <div className="jenkins-dropdown">
            {options.map((opt) => (
              <button
                key={opt.value}
                type="button"
                className={`jenkins-dropdown__item ${opt.value === value ? "bpe-select__item--selected" : ""}`}
                role="option"
                aria-selected={opt.value === value}
                onClick={() => handleSelect(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
